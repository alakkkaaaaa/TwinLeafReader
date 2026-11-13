package com.localreader.twinleaf.note

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import com.localreader.twinleaf.ink.InkInputSession
import com.localreader.twinleaf.ink.StylusContactSession
import com.localreader.twinleaf.ink.StylusContactDecision
import com.localreader.twinleaf.ink.HistoricalInkCache
import com.localreader.twinleaf.ink.InkBrush
import com.localreader.twinleaf.ink.InkEraseSession
import com.localreader.twinleaf.ink.InkPoint
import com.localreader.twinleaf.ink.InkStroke
import com.localreader.twinleaf.ink.InkTool
import com.localreader.twinleaf.ink.InkTailFilter
import com.localreader.twinleaf.ink.ReleaseSensitivity
import com.localreader.twinleaf.ink.beautifyInkStroke
import com.localreader.twinleaf.ink.InkStrokePainter
import com.localreader.twinleaf.ink.LiveInkRenderer
import com.localreader.twinleaf.ink.InkTipPrediction
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A vector, world-coordinate canvas. Fingers navigate; a stylus writes. */
class InfiniteNoteView(context:Context):View(context) {
    lateinit var store:NoteStore
    private val density=resources.displayMetrics.density
    private val gridPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth=1f }
    private val cursorOuter=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;color=Color.WHITE;strokeWidth=3f }
    private val cursorInner=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;color=0xCC253047.toInt();strokeWidth=1f }
    private var stylusId=-1
    private val inputSession=InkInputSession()
    private val contactSession=StylusContactSession(context)
    private val historyCache=HistoricalInkCache()
    private val strokePainter=InkStrokePainter(ovalBrushDot=false)
    private val liveRenderer=LiveInkRenderer(ovalBrushDot=false)
    private val predictionCleanup=Runnable { invalidate() }
    private val predictionPixels=minOf(12f*density,resources.displayMetrics.xdpi.coerceIn(80f,1000f)*3f/25.4f)
    private val tailFilter=InkTailFilter()
    private var preview=mutableListOf<InkPoint>()
    private var previewStroke:InkStroke?=null
    private var eraseSession:InkEraseSession?=null
    private var erased:List<InkStroke>?=null
    private var eraserX=Float.NaN;private var eraserY=Float.NaN
    private var navigationIds=emptySet<Int>()
    private var lastFocusX=0f;private var lastFocusY=0f;private var lastSpan=0f

    init { contentDescription="无限笔记画布";isFocusableInTouchMode=true }

    override fun onHoverEvent(event:MotionEvent):Boolean {
        if(event.pointerCount>0 && event.getToolType(event.actionIndex) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER) &&
            stylusId>=0 && event.deviceId==inputSession.deviceId) finishStylus(true,event.eventTime)
        return super.onHoverEvent(event)
    }
    override fun onWindowFocusChanged(hasWindowFocus:Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if(!hasWindowFocus && ::store.isInitialized) finishStylus(false)
    }
    override fun onFocusChanged(gainFocus:Boolean,direction:Int,previouslyFocusedRect:Rect?) {
        super.onFocusChanged(gainFocus,direction,previouslyFocusedRect)
        if(!gainFocus && ::store.isInitialized) finishStylus(false)
    }

    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(!::store.isInitialized) return
        canvas.drawColor(store.paperSettings.backgroundColor)
        val viewport=store.viewport
        canvas.save()
        canvas.translate(width/2f,height/2f);canvas.scale(viewport.scale,viewport.scale);canvas.translate(-viewport.centerX,-viewport.centerY)
        drawBackground(canvas,viewport)
        val visible=visibleWorld(viewport)
        val source=erased ?: store.strokes
        historyCache.draw(canvas,source,RectF(visible.left,visible.top,visible.right,visible.bottom),
            viewport.scale,bounds={ stroke -> stroke.noteBounds().let { RectF(it.left,it.top,it.right,it.bottom) } },
            render={ target,stroke -> drawStroke(target,stroke) })
        if(liveRenderer.draw(canvas,viewport.scale,predictionPixels,display?.refreshRate ?: 60f)) {
            removeCallbacks(predictionCleanup)
            postDelayed(predictionCleanup,InkTipPrediction.MAX_AGE_MS+1)
        }
        if(eraserX.isFinite() && eraserY.isFinite()) {
            val radius=store.eraserRadiusDp*density/viewport.scale
            cursorOuter.strokeWidth=3f*density/viewport.scale;cursorInner.strokeWidth=1f*density/viewport.scale
            canvas.drawCircle(eraserX,eraserY,radius,cursorOuter);canvas.drawCircle(eraserX,eraserY,radius,cursorInner)
        }
        canvas.restore()
    }

    private fun visibleWorld(viewport:NoteViewport)=NoteBounds(
        viewport.centerX-width/2f/viewport.scale,viewport.centerY-height/2f/viewport.scale,
        viewport.centerX+width/2f/viewport.scale,viewport.centerY+height/2f/viewport.scale)

    private fun drawBackground(canvas:Canvas,viewport:NoteViewport) {
        drawNotePaperGuides(canvas,visibleWorld(viewport),viewport.scale,store.paperSettings,gridPaint)
    }

    private fun drawStroke(canvas:Canvas,stroke:InkStroke) {
        strokePainter.draw(canvas,stroke)
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!::store.isInitialized || !store.ready) return true
        val action=event.actionMasked;val actionIndex=event.actionIndex
        val type=event.getToolType(actionIndex)
        val stylus=type==MotionEvent.TOOL_TYPE_STYLUS || type==MotionEvent.TOOL_TYPE_ERASER
        if(action==MotionEvent.ACTION_CANCEL) { finishStylus(false);navigationIds=emptySet();return true }
        if(action==MotionEvent.ACTION_DOWN && stylusId>=0) finishStylus(true,event.eventTime)
        if(stylusId>=0 && store.tool==InkTool.BROWSE) finishStylus(true,event.eventTime)
        if((action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) && stylusId<0 && stylus && store.tool!=InkTool.BROWSE) {
            beginStylus(event,actionIndex,type==MotionEvent.TOOL_TYPE_ERASER)
        }
        val active=event.findPointerIndex(stylusId)
        if(stylusId>=0 && event.deviceId==inputSession.deviceId &&
            (active<0 || !inputSession.owns(event.deviceId,stylusId,event.downTime) ||
                event.getToolType(active) !in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER))) {
            finishStylus(true,event.eventTime);return true
        }
        if(stylusId>=0 && event.deviceId!=inputSession.deviceId) return true
        if(active>=0) {
            if(action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
                for(history in 0 until event.historySize) addStylus(event.getHistoricalX(active,history),event.getHistoricalY(active,history),
                    event.getHistoricalPressure(active,history),event.getHistoricalAxisValue(MotionEvent.AXIS_TILT,active,history),
                    event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION,active,history),event.getHistoricalEventTime(history),contactSession.distance(event,active,history))
                addStylus(event.getX(active),event.getY(active),event.getPressure(active),
                    event.getAxisValue(MotionEvent.AXIS_TILT,active),event.getAxisValue(MotionEvent.AXIS_ORIENTATION,active),event.eventTime,contactSession.distance(event,active))
            }
            if((action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) && event.getPointerId(actionIndex)==stylusId)
                finishStylus(!(Build.VERSION.SDK_INT>=33 && event.flags and MotionEvent.FLAG_CANCELED!=0),event.eventTime)
            if(action==MotionEvent.ACTION_CANCEL) finishStylus(false)
            invalidate();return true
        }
        // An orphan stylus MOVE after a confirmed release cannot draw or pan the canvas.
        if((0 until event.pointerCount).all { event.getToolType(it)==MotionEvent.TOOL_TYPE_FINGER }) handleNavigation(event)
        invalidate();return true
    }

    private fun beginStylus(event:MotionEvent,index:Int,physicalEraser:Boolean) {
        stylusId=event.getPointerId(index);preview.clear();store.drawing=true;tailFilter.begin(ReleaseSensitivity.STANDARD)
        requestUnbufferedDispatch(event)
        inputSession.begin(event.deviceId,stylusId,event.downTime,event.eventTime)
        contactSession.begin(event)
        navigationIds=emptySet();requestFocus()
        val viewport=store.viewport
        val erasing=physicalEraser || store.tool==InkTool.ERASER
        if(erasing) {
            erased=store.strokes
            eraseSession=InkEraseSession(store.strokes,1f,1f,store.eraserRadiusDp*density/viewport.scale)
        } else {
            val highlight=store.tool==InkTool.HIGHLIGHT
            val brush=if(highlight) InkBrush.HIGHLIGHTER else store.brush
            val width=store.widthDp*density/viewport.scale*(if(highlight) 5f else 1f)
            previewStroke=InkStroke(emptyList(),store.color,width,highlight,brush,
                if(highlight) minOf(store.opacity,80f/255f) else store.opacity,store.pressureCurve)
            liveRenderer.begin(previewStroke!!.copy(points=preview))
        }
    }

    private fun addStylus(screenX:Float,screenY:Float,pressure:Float,tilt:Float,orientation:Float,time:Long,distance:Float?=null) {
        if(!inputSession.acceptTime(time)) return
        when(contactSession.sample(pressure,distance)) {
            StylusContactDecision.HOLD -> { liveRenderer.predictionAllowed=false;return }
            StylusContactDecision.END -> { finishStylus(true,time);return }
            StylusContactDecision.ACCEPT -> Unit
        }
        val point=screenToWorld(screenX,screenY)
        if(eraseSession!=null) {
            eraserX=point.first;eraserY=point.second
            eraseSession?.eraseAt(point.first,point.second)?.let { erased=it }
        } else if(preview.size<50_000) {
            val safePressure=pressure.takeIf(Float::isFinite)?.coerceIn(0f,10f) ?: 1f
            val next=InkPoint(point.first,point.second,safePressure,
                tilt.takeIf(Float::isFinite) ?: 0f,orientation.takeIf(Float::isFinite) ?: 0f,time,safePressure)
            val samples=if(contactSession.calibrated) listOf(next) else tailFilter.offer(next)
            liveRenderer.predictionAllowed=samples.isNotEmpty()
            for(sample in samples) {
                val last=preview.lastOrNull()
                if(last==null || hypot(sample.x-last.x,sample.y-last.y)>.35f/store.viewport.scale) preview.add(sample)
            }
        }
    }

    private fun finishStylus(commit:Boolean,releaseTimeMillis:Long=preview.lastOrNull()?.timeMillis ?: 0L) {
        if(commit) {
            val result=erased
            if(result!=null) store.change(result)
            else previewStroke?.let { stroke -> if(preview.isNotEmpty()) {
                val trimmed=if(contactSession.calibrated) preview.toList() else tailFilter.finish(preview.toList(),store.viewport.scale,store.viewport.scale,releaseTimeMillis)
                val raw=stroke.copy(points=trimmed)
                val completed=beautifyInkStroke(raw,store.beautifyLevel,store.autoStraighten,
                    store.viewport.scale,store.viewport.scale,null,store.straightenSensitivity)
                if(trimmed.isNotEmpty()) store.change(store.strokes+completed)
            } }
        }
        tailFilter.discard()
        stylusId=-1;preview.clear();previewStroke=null;eraseSession=null;erased=null
        liveRenderer.clear();removeCallbacks(predictionCleanup)
        inputSession.clear()
        contactSession.end()
        eraserX=Float.NaN;eraserY=Float.NaN;store.drawing=false;invalidate()
    }

    private fun handleNavigation(event:MotionEvent) {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> resetNavigation(event)
            MotionEvent.ACTION_MOVE -> {
                if(event.pointerCount<1) return
                val focusX=(0 until event.pointerCount).sumOf { event.getX(it).toDouble() }.toFloat()/event.pointerCount
                val focusY=(0 until event.pointerCount).sumOf { event.getY(it).toDouble() }.toFloat()/event.pointerCount
                val span=if(event.pointerCount>=2) hypot(event.getX(1)-event.getX(0),event.getY(1)-event.getY(0)) else 0f
                val ids=(0 until event.pointerCount).map { event.getPointerId(it) }.toSet()
                if(ids!=navigationIds) { resetNavigation(event);return }
                val old=store.viewport
                if(event.pointerCount>=2 && lastSpan>4f && span>4f) {
                    val anchor=screenToWorld(lastFocusX,lastFocusY)
                    val nextScale=(old.scale*span/lastSpan).coerceIn(.2f,8f)
                    val centerX=anchor.first-(focusX-width/2f)/nextScale
                    val centerY=anchor.second-(focusY-height/2f)/nextScale
                    store.updateViewport(NoteViewport(centerX,centerY,nextScale))
                } else {
                    store.updateViewport(old.copy(centerX=old.centerX-(focusX-lastFocusX)/old.scale,
                        centerY=old.centerY-(focusY-lastFocusY)/old.scale))
                }
                lastFocusX=focusX;lastFocusY=focusY;lastSpan=span
            }
            MotionEvent.ACTION_POINTER_UP -> navigationIds=emptySet()
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { navigationIds=emptySet();store.updateViewport(store.viewport,true) }
        }
    }

    private fun resetNavigation(event:MotionEvent) {
        navigationIds=(0 until event.pointerCount).map { event.getPointerId(it) }.toSet()
        lastFocusX=(0 until event.pointerCount).sumOf { event.getX(it).toDouble() }.toFloat()/event.pointerCount
        lastFocusY=(0 until event.pointerCount).sumOf { event.getY(it).toDouble() }.toFloat()/event.pointerCount
        lastSpan=if(event.pointerCount>=2) hypot(event.getX(1)-event.getX(0),event.getY(1)-event.getY(0)) else 0f
    }

    private fun screenToWorld(x:Float,y:Float):Pair<Float,Float> {
        val viewport=store.viewport
        return viewport.centerX+(x-width/2f)/viewport.scale to viewport.centerY+(y-height/2f)/viewport.scale
    }

    override fun onDetachedFromWindow() {
        if(::store.isInitialized) { finishStylus(false);store.persist() }
        historyCache.clear()
        super.onDetachedFromWindow()
    }
}

/** Draws guides in world coordinates; all lattice indices are measured from world origin. */
internal fun drawNotePaperGuides(canvas:Canvas,bounds:NoteBounds,scale:Float,
    settings:NotePaperSettings,paint:Paint) {
    if(settings.pattern==NoteBackground.BLANK || !scale.isFinite() || scale<=0f) return
    val spacing=settings.spacing.coerceIn(16f,80f)
    val opacity=settings.lineOpacity.coerceIn(0f,1f)
    if(opacity<=0f) return
    val paper=settings.backgroundColor
    val luminance=(.2126f*Color.red(paper)+.7152f*Color.green(paper)+.0722f*Color.blue(paper))/255f
    val guideColor=if(luminance<.48f) 0xFFD9E4F5.toInt() else 0xFF526B91.toInt()
    fun setLine(alpha:Float) {
        paint.color=Color.argb((alpha.coerceIn(0f,1f)*255).toInt(),
            Color.red(guideColor),Color.green(guideColor),Color.blue(guideColor))
    }
    fun indexStart(value:Float,step:Float)=ceil(value.toDouble()/step).toInt()
    fun indexEnd(value:Float,step:Float)=floor(value.toDouble()/step).toInt()
    // A line closer than about 12 screen pixels aliases; draw every Nth world-indexed line.
    fun stride(step:Float)=notePaperLodStride(step,scale)
    paint.style=Paint.Style.STROKE
    paint.strokeWidth=1f/scale
    canvas.save();canvas.clipRect(bounds.left,bounds.top,bounds.right,bounds.bottom)
    when(settings.pattern) {
        NoteBackground.BLANK -> Unit
        NoteBackground.LINED,NoteBackground.GRID -> {
            setLine(opacity)
            val skip=stride(spacing)
            for(y in indexStart(bounds.top,spacing)..indexEnd(bounds.bottom,spacing))
                if(y%skip==0) canvas.drawLine(bounds.left,y*spacing,bounds.right,y*spacing,paint)
            if(settings.pattern==NoteBackground.GRID)
                for(x in indexStart(bounds.left,spacing)..indexEnd(bounds.right,spacing))
                    if(x%skip==0) canvas.drawLine(x*spacing,bounds.top,x*spacing,bounds.bottom,paint)
        }
        NoteBackground.DOT_GRID -> {
            // Dots below 14 screen pixels spacing make a moiré field, so this LOD omits them.
            if(spacing*scale>=14f) {
                setLine(opacity)
                val radius=1.3f/scale
                paint.style=Paint.Style.FILL
                for(x in indexStart(bounds.left,spacing)..indexEnd(bounds.right,spacing))
                    for(y in indexStart(bounds.top,spacing)..indexEnd(bounds.bottom,spacing))
                        canvas.drawCircle(x*spacing,y*spacing,radius,paint)
            }
        }
        NoteBackground.ENGINEERING -> {
            val minorVisible=spacing*scale>=12f
            val majorStride=5*stride(5f*spacing)
            fun engineeringLine(index:Int,vertical:Boolean) {
                val major=isMajorGridLine(index)
                if(major && index%majorStride!=0) return
                if(!major && !minorVisible) return
                setLine(if(major) min(1f,opacity*1.8f) else opacity*.55f)
                val position=index*spacing
                if(vertical) canvas.drawLine(position,bounds.top,position,bounds.bottom,paint)
                else canvas.drawLine(bounds.left,position,bounds.right,position,paint)
            }
            for(x in indexStart(bounds.left,spacing)..indexEnd(bounds.right,spacing)) engineeringLine(x,true)
            for(y in indexStart(bounds.top,spacing)..indexEnd(bounds.bottom,spacing)) engineeringLine(y,false)
        }
        NoteBackground.ISOMETRIC -> {
            val root3=sqrt(3.0).toFloat()
            val row=spacing*root3/2f
            val skip=stride(row)
            setLine(opacity)
            for(k in indexStart(bounds.top,row)..indexEnd(bounds.bottom,row))
                if(k%skip==0) canvas.drawLine(bounds.left,k*row,bounds.right,k*row,paint)
            // x ± y/√3 = k·spacing produces the two diagonal families at ±60°.
            for(sign in listOf(-1f,1f)) {
                val values=floatArrayOf(bounds.left+sign*bounds.top/root3,
                    bounds.left+sign*bounds.bottom/root3,
                    bounds.right+sign*bounds.top/root3,bounds.right+sign*bounds.bottom/root3)
                for(k in indexStart(values.minOrNull() ?: 0f,spacing)..indexEnd(values.maxOrNull() ?: 0f,spacing))
                    if(k%skip==0) canvas.drawLine(k*spacing-sign*bounds.top/root3,bounds.top,
                        k*spacing-sign*bounds.bottom/root3,bounds.bottom,paint)
            }
        }
    }
    canvas.restore()
}
