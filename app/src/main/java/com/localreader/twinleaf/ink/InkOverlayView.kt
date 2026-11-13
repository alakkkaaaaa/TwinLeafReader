package com.localreader.twinleaf.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/** Only stylus input draws ink; two fingers transform the viewport without turning pages. */
class InkOverlayView(context:Context):View(context) {
    lateinit var store:InkStore
    var pageRects:Map<Int,RectF> = emptyMap()
    var zoom=1f
    var panX=0f
    var panY=0f
    var onViewportChanged: (InkViewport) -> Unit = {}
    private var touchBlocked=false
    private var pinchIds: Set<Int> = emptySet()
    private var pinchStarted=false
    private var firstSpan=0f;private var lastSpan=0f
    private var firstFocusX=0f;private var firstFocusY=0f
    private var lastFocusX=0f;private var lastFocusY=0f
    private val touchSlop=ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var pointer=-1
    private val inputSession=InkInputSession()
    private val contactSession=StylusContactSession(context)
    private var lastDiagnosticTime=0L
    private val historyCaches=mutableMapOf<Int,HistoricalInkCache>()
    private val historyPainter=InkStrokePainter()
    private val liveRenderer=LiveInkRenderer()
    private val predictionCleanup=Runnable { invalidate() }
    private val predictionPixels=minOf(12f*resources.displayMetrics.density,
        resources.displayMetrics.xdpi.coerceIn(80f,1000f)*3f/25.4f)
    private var page=-1
    private var rect=RectF()
    private var points=mutableListOf<InkPoint>()
    private val tailFilter=InkTailFilter()
    private var previousPressure=Float.NaN
    private var erased=emptyList<InkStroke>()
    private var eraserSession:InkEraseSession?=null
    private var erasing=false
    private var eraserX=Float.NaN;private var eraserY=Float.NaN
    private val eraserOuter=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;color=0xEFFFFFFF.toInt() }
    private val eraserInner=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;color=0xCC252A32.toInt() }
    private var strokeColor=0;private var strokeWidth=0f;private var highlight=false
    private var strokeBrush=InkBrush.BALLPOINT
    private var strokeOpacity=1f
    private var strokeCurve=PressureCurve.LINEAR
    private var keyPrimary=false;private var keySecondary=false
    init { contentDescription="PDF 手写批注层";isFocusableInTouchMode=true }
    override fun onAttachedToWindow() { super.onAttachedToWindow();requestFocus() }
    override fun onFocusChanged(gainFocus:Boolean,direction:Int,previouslyFocusedRect:Rect?) {
        super.onFocusChanged(gainFocus,direction,previouslyFocusedRect)
        if(!gainFocus) cancelInput()
    }
    override fun onWindowFocusChanged(hasWindowFocus:Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if(!hasWindowFocus) cancelInput()
    }
    override fun onHoverEvent(event:MotionEvent):Boolean {
        if(event.pointerCount>0 && event.getToolType(event.actionIndex) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER)) {
            if(pointer>=0 && event.deviceId==inputSession.deviceId) finish(true,event.eventTime)
            requestFocus()
        }
        return super.onHoverEvent(event)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(!store.visible) return
        canvas.save();canvas.translate(width/2f+panX,height/2f+panY);canvas.scale(zoom,zoom);canvas.translate(-width/2f,-height/2f)
        val visible=RectF(pageX(0f),pageY(0f),pageX(width.toFloat()),pageY(height.toFloat()))
        historyCaches.keys.filter { it !in pageRects }.forEach { historyCaches.remove(it)?.clear() }
        pageRects.forEach { (p,r) ->
            val strokes=if(p==page && erasing) erased else store.pages[p].orEmpty()
            if(RectF.intersects(visible,r)) {
                val cache=historyCaches.getOrPut(p) { HistoricalInkCache() }
                val clipped=RectF(visible).apply { intersect(r) }
                cache.draw(canvas,strokes,clipped,zoom,zoom,
                    listOf(r.left,r.top,r.right,r.bottom),
                    bounds={ stroke ->
                        val pad=stroke.width*r.width()*1.5f
                        if(stroke.points.isEmpty()) RectF() else RectF(
                            r.left+stroke.points.minOf { it.x }*r.width()-pad,
                            r.top+stroke.points.minOf { it.y }*r.height()-pad,
                            r.left+stroke.points.maxOf { it.x }*r.width()+pad,
                            r.top+stroke.points.maxOf { it.y }*r.height()+pad)
                    },render={ target,stroke ->
                        target.save();target.clipRect(r);historyPainter.draw(target,stroke,r);target.restore() })
            }
        }
        if(pointer>=0 && !erasing && liveRenderer.draw(canvas,zoom,predictionPixels,display?.refreshRate ?: 60f)) {
            removeCallbacks(predictionCleanup)
            postDelayed(predictionCleanup,InkTipPrediction.MAX_AGE_MS+1)
        }
        if(pointer>=0 && erasing && eraserX.isFinite() && eraserY.isFinite()) {
            val radius=store.eraserRadiusDp*resources.displayMetrics.density/zoom.coerceAtLeast(.1f)
            eraserOuter.strokeWidth=3f*resources.displayMetrics.density/zoom.coerceAtLeast(.1f)
            eraserInner.strokeWidth=1f*resources.displayMetrics.density/zoom.coerceAtLeast(.1f)
            canvas.drawCircle(eraserX,eraserY,radius,eraserOuter)
            canvas.drawCircle(eraserX,eraserY,radius,eraserInner)
        }
        canvas.restore()
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(!store.ready || !store.visible || store.tool==InkTool.BROWSE) {
            if(pointer>=0) cancelInput()
            return false
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        val action=e.actionMasked;val index=e.actionIndex
        if(action==MotionEvent.ACTION_DOWN && pointer>=0) finish(true,e.eventTime)
        if(e.eventTime-lastDiagnosticTime>=100 && (0 until e.pointerCount).any { e.getToolType(it)==MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(it)==MotionEvent.TOOL_TYPE_ERASER }) {
            lastDiagnosticTime=e.eventTime
            store.stylusDiagnostic="事件 ${MotionEvent.actionToString(action)} · 压力 ${"%.2f".format(e.getPressure(index))} · 倾斜 ${"%.2f".format(e.getAxisValue(MotionEvent.AXIS_TILT,index))} · 方向 ${"%.2f".format(e.getAxisValue(MotionEvent.AXIS_ORIENTATION,index))} · 按键 ${e.buttonState} · 历史点 ${e.historySize}"
        }
        handleFingerTransform(e)
        if(action==MotionEvent.ACTION_CANCEL) { cancelInput();return true }
        if((action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) && pointer<0) {
            val type=e.getToolType(index)
            if(type!=MotionEvent.TOOL_TYPE_STYLUS && type!=MotionEvent.TOOL_TYPE_ERASER) return true
            val hit=pageRects.entries.firstOrNull { it.value.contains(pageX(e.getX(index)),pageY(e.getY(index))) } ?: return true
            pointer=e.getPointerId(index);page=hit.key;rect=RectF(hit.value);points.clear();store.drawing=true;requestFocus()
            requestUnbufferedDispatch(e)
            inputSession.begin(e.deviceId,pointer,e.downTime,e.eventTime)
            contactSession.begin(e)
            tailFilter.begin(store.releaseSensitivity);previousPressure=Float.NaN
            val buttonAction=when {
                keyPrimary || e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY!=0 -> store.primaryButtonAction
                keySecondary || e.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY!=0 -> store.secondaryButtonAction
                else -> StylusButtonAction.NONE
            }
            erasing=type==MotionEvent.TOOL_TYPE_ERASER || store.tool==InkTool.ERASER || buttonAction==StylusButtonAction.ERASER
            erased=store.pages[page].orEmpty()
            eraserSession=if(erasing) InkEraseSession(erased,rect.width(),rect.height(),
                store.eraserRadiusDp*resources.displayMetrics.density/zoom.coerceAtLeast(.1f)) else null
            strokeBrush=when(buttonAction) {
                StylusButtonAction.HIGHLIGHTER -> InkBrush.HIGHLIGHTER
                StylusButtonAction.PEN -> InkBrush.BALLPOINT
                else -> if(store.tool==InkTool.HIGHLIGHT) InkBrush.HIGHLIGHTER else store.brush
            }
            highlight=strokeBrush==InkBrush.HIGHLIGHTER
            strokeColor=store.color;strokeWidth=store.width*(if(highlight) 5f else 1f)
            strokeOpacity=if(highlight) minOf(store.opacity,80f/255f) else store.opacity
            strokeCurve=store.pressureCurve
            if(!erasing) liveRenderer.begin(InkStroke(points,strokeColor,strokeWidth,highlight,strokeBrush,strokeOpacity,strokeCurve),rect)
        }
        val active=e.findPointerIndex(pointer)
        if(pointer>=0 && e.deviceId==inputSession.deviceId &&
            (active<0 || !inputSession.owns(e.deviceId,pointer,e.downTime) ||
                e.getToolType(active) !in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER))) {
            finish(true,e.eventTime);return true
        }
        if(pointer>=0 && e.deviceId!=inputSession.deviceId) return true
        if(active>=0) {
            if(action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
                for(h in 0 until e.historySize) add(e.getHistoricalX(active,h),e.getHistoricalY(active,h),e.getHistoricalPressure(active,h),
                    e.getHistoricalAxisValue(MotionEvent.AXIS_TILT,active,h),e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION,active,h),e.getHistoricalEventTime(h),contactSession.distance(e,active,h))
                add(e.getX(active),e.getY(active),e.getPressure(active),e.getAxisValue(MotionEvent.AXIS_TILT,active),e.getAxisValue(MotionEvent.AXIS_ORIENTATION,active),e.eventTime,contactSession.distance(e,active))
            }
            if((action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) && e.getPointerId(index)==pointer) {
                val canceled=Build.VERSION.SDK_INT>=33 && e.flags and MotionEvent.FLAG_CANCELED!=0
                // MOVE already contains the last reliable contact sample. Many stylus drivers
                // report a displaced UP coordinate after the nib has left the glass.
                finish(!canceled,e.eventTime)
            }
        }
        invalidate();return true
    }
    override fun onGenericMotionEvent(event:MotionEvent):Boolean {
        val buttonEvent=event.actionMasked==MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked==MotionEvent.ACTION_BUTTON_RELEASE
        if(buttonEvent) {
            val pressed=event.actionMasked==MotionEvent.ACTION_BUTTON_PRESS
            when(event.actionButton) {
                MotionEvent.BUTTON_STYLUS_PRIMARY -> keyPrimary=pressed
                MotionEvent.BUTTON_STYLUS_SECONDARY -> keySecondary=pressed
            }
        }
        if(::store.isInitialized && (buttonEvent || event.actionMasked==MotionEvent.ACTION_HOVER_MOVE))
            store.stylusDiagnostic="悬停/按键 ${MotionEvent.actionToString(event.actionMasked)} · 压力 ${"%.2f".format(event.pressure)} · 倾斜 ${"%.2f".format(event.getAxisValue(MotionEvent.AXIS_TILT))} · 方向 ${"%.2f".format(event.getAxisValue(MotionEvent.AXIS_ORIENTATION))} · 按键 ${event.buttonState}"
        return if(buttonEvent) true else super.onGenericMotionEvent(event)
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        val key=KeyEvent.keyCodeToString(keyCode)
        if(key.contains("STYLUS_BUTTON")) {
            if(key.contains("PRIMARY")) keyPrimary=true else if(key.contains("SECONDARY")) keySecondary=true
            if(::store.isInitialized) store.stylusDiagnostic="按键按下 $key"
            return true
        }
        return super.onKeyDown(keyCode,event)
    }
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean {
        val key=KeyEvent.keyCodeToString(keyCode)
        if(key.contains("STYLUS_BUTTON")) {
            if(key.contains("PRIMARY")) keyPrimary=false else if(key.contains("SECONDARY")) keySecondary=false
            if(::store.isInitialized) store.stylusDiagnostic="按键松开 $key"
            return true
        }
        return super.onKeyUp(keyCode,event)
    }
    private fun clearButtonState() { keyPrimary=false;keySecondary=false }
    private fun cancelInput() {
        clearButtonState();resetPinch();touchBlocked=false
        if(::store.isInitialized) finish(false)
        else { pointer=-1;page=-1;points.clear();tailFilter.discard();previousPressure=Float.NaN }
    }
    private fun currentStroke()=InkStroke(points.toList(),strokeColor,strokeWidth,highlight,strokeBrush,strokeOpacity,strokeCurve)
    private fun resetPinch() { pinchIds=emptySet();pinchStarted=false }
    private fun handleFingerTransform(e: MotionEvent) {
        if(Build.VERSION.SDK_INT>=33 && e.flags and MotionEvent.FLAG_CANCELED!=0) {
            touchBlocked=true;resetPinch();return
        }
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touchBlocked=false;resetPinch() }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> { touchBlocked=false;resetPinch();return }
            MotionEvent.ACTION_POINTER_UP -> { resetPinch();return }
        }
        // Once a stylus or extra contact is present, fingers stay blocked until all lift.
        // A pen plus a palm must never be interpreted as a two-finger zoom.
        if(pointer>=0 || e.pointerCount>2 || (0 until e.pointerCount).any { e.getToolType(it)!=MotionEvent.TOOL_TYPE_FINGER }) {
            touchBlocked=true;resetPinch();return
        }
        if(touchBlocked || e.pointerCount!=2) { resetPinch();return }
        val ids=setOf(e.getPointerId(0),e.getPointerId(1))
        val span=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0))
        val x=(e.getX(0)+e.getX(1))/2f;val y=(e.getY(0)+e.getY(1))/2f
        if(ids!=pinchIds) {
            pinchIds=ids;firstSpan=span;lastSpan=span
            firstFocusX=x;firstFocusY=y;lastFocusX=x;lastFocusY=y;return
        }
        if(e.actionMasked!=MotionEvent.ACTION_MOVE || span<touchSlop || lastSpan<touchSlop) return
        if(!pinchStarted && (abs(span-firstSpan)>touchSlop || hypot(x-firstFocusX,y-firstFocusY)>touchSlop)) pinchStarted=true
        if(pinchStarted) {
            val next=transformInkViewport(InkViewport(zoom,panX,panY),width.toFloat(),height.toFloat(),lastFocusX,lastFocusY,x,y,span/lastSpan)
            zoom=next.zoom;panX=next.panX;panY=next.panY
            onViewportChanged(next);invalidate()
            lastSpan=span;lastFocusX=x;lastFocusY=y
        }
    }
    private fun add(x:Float,y:Float,pressure:Float,tilt:Float,orientation:Float,time:Long,distance:Float?=null) {
        if(!inputSession.acceptTime(time)) return
        when(contactSession.sample(pressure,distance)) {
            StylusContactDecision.HOLD -> { liveRenderer.predictionAllowed=false;return }
            StylusContactDecision.END -> { finish(true,time);return }
            StylusContactDecision.ACCEPT -> Unit
        }
        val raw=(if(pressure.isFinite() && pressure>=0f) pressure else points.lastOrNull()?.rawPressure ?: 1f).coerceIn(0f,10f)
        val smoothed=if(previousPressure.isFinite()) previousPressure*.55f+raw*.45f else raw
        previousPressure=smoothed
        val localX=pageX(x);val localY=pageY(y)
        val normalizedX=(localX-rect.left)/rect.width();val normalizedY=(localY-rect.top)/rect.height()
        val p=InkPoint(normalizedX.coerceIn(0f,1f),normalizedY.coerceIn(0f,1f),
            smoothed,if(tilt.isFinite()) tilt else 0f,if(orientation.isFinite()) orientation else 0f,time,raw)
        if(erasing) {
            eraserX=localX;eraserY=localY
            eraserSession?.eraseAt(normalizedX,normalizedY)?.let { erased=it }
        }
        else {
            val samples=if(contactSession.calibrated) listOf(p) else tailFilter.offer(p)
            liveRenderer.predictionAllowed=samples.isNotEmpty()
            for(sample in samples) if(points.size<20000 &&
                (points.lastOrNull()?.let { kotlin.math.hypot((it.x-sample.x)*rect.width()*zoom,(it.y-sample.y)*rect.height()*zoom)>.35f }!=false))
                points.add(sample)
        }
    }
    private fun pageX(x:Float)=(x-width/2f-panX)/zoom+width/2f
    private fun pageY(y:Float)=(y-height/2f-panY)/zoom+height/2f
    private fun finish(commit:Boolean,releaseTimeMillis:Long=points.lastOrNull()?.timeMillis ?: 0L) {
        if(pointer>=0 && commit) {
            if(erasing) store.change(page,erased)
            else if(points.isNotEmpty()) {
                val trimmed=if(contactSession.calibrated) points.toList() else tailFilter.finish(points.toList(),rect.width(),rect.height(),releaseTimeMillis)
                if(trimmed.isNotEmpty()) {
                    val completed=beautifyInkStroke(currentStroke().copy(points=trimmed),store.beautifyLevel,store.autoStraighten,
                        rect.width(),rect.height(),InkBeautifyBounds(0f,0f,1f,1f),store.straightenSensitivity)
                    store.change(page,store.pages[page].orEmpty()+completed)
                }
            }
        }
        pointer=-1;page=-1;points.clear();tailFilter.discard();previousPressure=Float.NaN;eraserSession=null
        liveRenderer.clear();removeCallbacks(predictionCleanup)
        inputSession.clear()
        contactSession.end()
        eraserX=Float.NaN;eraserY=Float.NaN
        store.drawing=false;invalidate()
    }
    override fun onDetachedFromWindow() {
        cancelInput();historyCaches.values.forEach { it.clear() };historyCaches.clear()
        super.onDetachedFromWindow()
    }
}
