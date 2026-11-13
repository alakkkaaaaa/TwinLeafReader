package com.localreader.twinleaf.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.animation.DecelerateInterpolator
import kotlin.math.*

/** PDF/EPUB and flattened ink share real textures on a moving fold line.
 * No document rendering, bitmap cropping or bitmap mirroring runs per turn.
 */
class CurlBookView(context:Context):View(context),PageTurnTarget {
    var current:Bitmap?=null
        set(value) {
            if(isTurning && value!==field && value!==targetFrame) abortTurn()
            field=value
        }
    var previous:Bitmap?=null
    var next:Bitmap?=null
    var onTurn:(Int)->Unit={}
    var onSpine:()->Unit={}
    var night=false
    private var direction=0
    private var progress=0f
    private var touchY=.84f
    private var verticalTravel=0f
    private var downX=0f;private var downY=0f
    private var pointer=-1
    private var dragging=false
    private var gestureRecognized=false
    private var velocityTracker:VelocityTracker?=null
    private val touchSlop=max(ViewConfiguration.get(context).scaledTouchSlop*.7f,4f*resources.displayMetrics.density)
    private var animator:ValueAnimator?=null
    private var oldFrame:Bitmap?=null
    private var targetFrame:Bitmap?=null
    private var frontShader:BitmapShader?=null
    private var backShader:BitmapShader?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val curve=CurlGeometry()
    private val frontMesh=CurlMesh()
    private val backMesh=CurlMesh()
    private val backPath=Path()
    private val pageRect=RectF()
    private val shadowPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowMatrix=Matrix()
    private val foldShadow=LinearGradient(-1f,0f,1f,0f,
        intArrayOf(Color.TRANSPARENT,0x36000000,0x16000000,Color.TRANSPARENT),
        floatArrayOf(0f,.48f,.72f,1f),Shader.TileMode.CLAMP)
    private val edgePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0x40807060;style=Paint.Style.STROKE;strokeWidth=1f
    }
    private val spinePaint=Paint()
    val isTurning:Boolean get()=direction!=0 || animator!=null

    init {
        isClickable=true;contentDescription="双页阅读区，左右滑动翻页，点击书脊打开设置"
        // Hardware drawVertices arrived in API 29. Android 8/9 use software rendering.
        if(Build.VERSION.SDK_INT<29) setLayerType(LAYER_TYPE_SOFTWARE,null)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh);abortTurn()
        pageRect.set(0f,0f,w.toFloat(),h.toFloat())
        val half=w/2f
        spinePaint.shader=LinearGradient(half-12f,0f,half+12f,0f,
            intArrayOf(Color.TRANSPARENT,0x28231b10,Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        canvas.clipRect(0,0,width,height)
        canvas.drawColor(if(night) Color.rgb(32,32,30) else Color.rgb(250,247,239))
        val old=oldFrame ?: current ?: return
        val target=targetFrame
        val half=width/2f
        paint.shader=null
        if(direction==0 || target==null || progress<=0f) canvas.drawBitmap(old,null,pageRect,paint)
        else if(progress>=1f) canvas.drawBitmap(target,null,pageRect,paint)
        else {
            canvas.drawBitmap(target,null,pageRect,paint)
            canvas.save()
            if(direction>0) canvas.clipRect(0f,0f,half,height.toFloat())
            else canvas.clipRect(half,0f,width.toFloat(),height.toFloat())
            canvas.drawBitmap(old,null,pageRect,paint)
            canvas.restore()
            // The spine belongs underneath the lifted paper, never across its reverse face.
            canvas.drawRect(half-12f,0f,half+12f,height.toFloat(),spinePaint)

            canvas.save()
            if(direction<0) { canvas.translate(width.toFloat(),0f);canvas.scale(-1f,1f) }
            canvas.translate(half,0f)
            curve.configure(half,height.toFloat(),progress,touchY,verticalTravel)
            frontMesh.update(curve,false,old.width,old.height,direction>0,direction<0)
            backMesh.update(curve,true,target.width,target.height,direction<0,direction>0)
            outline(backMesh,backPath)

            // Cast shadow follows the actual reverse sheet.
            canvas.save()
            val lift=sin(PI.toFloat()*progress)
            canvas.translate(-curve.nx*curve.radius*.25f,curve.radius*.1f)
            shadowPaint.shader=null;shadowPaint.style=Paint.Style.FILL
            shadowPaint.color=Color.argb((24*lift).toInt(),18,16,12)
            canvas.drawPath(backPath,shadowPaint)
            shadowPaint.style=Paint.Style.STROKE
            for(i in 8 downTo 1) {
                shadowPaint.strokeWidth=i*curve.radius*.09f
                shadowPaint.alpha=(4*lift).toInt()
                canvas.drawPath(backPath,shadowPaint)
            }
            canvas.restore()
            drawFace(canvas,frontMesh,frontShader)

            // Contact gradient follows the cylinder silhouette in fold coordinates.
            shadowPaint.style=Paint.Style.FILL;shadowPaint.alpha=255
            shadowMatrix.reset();shadowMatrix.setScale(curve.radius*1.8f,1f)
            shadowMatrix.postTranslate(curve.fold+curve.radius,0f)
            shadowMatrix.postRotate(curve.angleDegrees)
            foldShadow.setLocalMatrix(shadowMatrix);shadowPaint.shader=foldShadow
            canvas.drawRect(-half,0f,half,height.toFloat(),shadowPaint)
            shadowPaint.shader=null
            drawFace(canvas,backMesh,backShader)
            canvas.drawPath(backPath,edgePaint)
            canvas.restore()
        }
        if(direction==0 || target==null || progress<=0f || progress>=1f)
            canvas.drawRect(half-12f,0f,half+12f,height.toFloat(),spinePaint)
        paint.shader=null
    }
    private fun drawFace(canvas:Canvas,mesh:CurlMesh,shader:BitmapShader?) {
        paint.shader=shader
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES,mesh.positions.size,mesh.positions,0,
            mesh.texture,0,mesh.colors,0,mesh.indices,0,mesh.indices.size,paint)
        paint.shader=null
    }
    private fun outline(mesh:CurlMesh,path:Path) {
        path.rewind()
        fun add(row:Int,col:Int,first:Boolean=false) {
            val i=(row*(mesh.columns+1)+col)*2
            if(first) path.moveTo(mesh.positions[i],mesh.positions[i+1]) else path.lineTo(mesh.positions[i],mesh.positions[i+1])
        }
        add(0,0,true)
        for(col in 1..mesh.columns) add(0,col)
        for(row in 1..mesh.rows) add(row,mesh.columns)
        for(col in mesh.columns-1 downTo 0) add(mesh.rows,col)
        for(row in mesh.rows-1 downTo 0) add(row,0)
        path.close()
    }
    override fun turnPage(direction:Int):Boolean {
        if(!isEnabled || pointer>=0 || direction !in listOf(-1,1) || !begin(direction)) return false
        touchY=.94f;verticalTravel=0f
        settle(true)
        return true
    }
    private fun begin(dir:Int):Boolean {
        val source=current ?: return false
        val target=(if(dir>0) next else previous) ?: return false
        if(isTurning || target===source || width<2 || height<1) return false
        direction=dir;oldFrame=source;targetFrame=target
        frontShader=BitmapShader(source,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
        backShader=BitmapShader(target,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
        return true
    }
    private fun settle(commit:Boolean) {
        if(direction==0 || animator!=null) return
        val dir=direction
        val end=if(commit) 1f else 0f
        val animation=ValueAnimator.ofFloat(progress,end).apply {
            duration=if(dragging) (100+240*abs(end-progress)).toLong() else 430L
            interpolator=DecelerateInterpolator(1.25f)
            addUpdateListener { progress=it.animatedValue as Float;postInvalidateOnAnimation() }
            addListener(object:AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation:Animator) {
                    val target=targetFrame
                    clearFrames()
                    if(commit && target!=null) { current=target;onTurn(dir) }
                    invalidate()
                }
            })
        }
        animator=animation;animation.start()
    }
    private fun clearFrames() {
        direction=0;progress=0f;animator=null;dragging=false
        oldFrame=null;targetFrame=null;frontShader=null;backShader=null
    }
    private fun abortTurn() {
        animator?.removeAllListeners();animator?.removeAllUpdateListeners();animator?.cancel()
        clearFrames();releasePointer()
    }
    private fun releasePointer() {
        pointer=-1;velocityTracker?.recycle();velocityTracker=null
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun setEnabled(enabled:Boolean) {
        if(!enabled && isTurning) abortTurn()
        super.setEnabled(enabled)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!isEnabled) return false
        if(animator!=null) {
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) releasePointer()
            return true
        }
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer=event.getPointerId(0);downX=event.x;downY=event.y
                dragging=false;gestureRecognized=false;verticalTravel=0f
                touchY=(downY/height.coerceAtLeast(1)).coerceIn(0f,1f)
                velocityTracker?.recycle();velocityTracker=VelocityTracker.obtain().also { it.addMovement(event) }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL -> {
                gestureRecognized=true;settle(false);releasePointer();return true
            }
        }
        val index=event.findPointerIndex(pointer)
        if(index<0) return true
        val x=event.getX(index);val y=event.getY(index);val dx=x-downX
        velocityTracker?.addMovement(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if(!gestureRecognized && abs(dx)>touchSlop && abs(dx)>abs(y-downY)*1.1f) {
                    gestureRecognized=true;dragging=begin(if(dx<0f) 1 else -1)
                }
                if(dragging) {
                    progress=TurnSensitivity.progress(if(direction>0) -dx else dx,width)
                    verticalTravel=(y-downY)/height.coerceAtLeast(1)
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.computeCurrentVelocity(1000)
                if(dragging) {
                    val sign=if(direction>0) -1f else 1f
                    progress=TurnSensitivity.progress(dx*sign,width)
                    verticalTravel=(y-downY)/height.coerceAtLeast(1)
                    settle(TurnSensitivity.shouldCommit(dx*sign,width,resources.displayMetrics.density,
                        (velocityTracker?.getXVelocity(pointer) ?: 0f)*sign))
                } else if(!gestureRecognized && abs(dx)<=touchSlop && abs(y-downY)<=touchSlop) {
                    performClick()
                    if(abs(downX-width/2f)<max(width*.055f,26f*resources.displayMetrics.density)) onSpine()
                    else if(begin(if(downX>width/2f) 1 else -1)) {
                        touchY=if(downY<height*.3f) .06f else .94f
                        settle(true)
                    }
                }
                releasePointer()
            }
        }
        return true
    }
    override fun performClick():Boolean { super.performClick();return true }
    override fun onDetachedFromWindow() { abortTurn();super.onDetachedFromWindow() }
}
