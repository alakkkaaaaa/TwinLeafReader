package com.localreader.twinleaf.epub

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.localreader.twinleaf.ui.components.PageTurnTarget
import kotlin.math.abs
import kotlin.math.max

/**
 * A deliberately flat page transition for single-page EPUB reading.  It keeps both bitmaps on
 * screen while dragging, like two cards on a desk, and never applies the book-curl mesh used by
 * landscape spreads.
 */
internal class EpubCardTurnView(context: Context) : View(context), PageTurnTarget {
    var current: Bitmap? = null
    var previous: Bitmap? = null
    var next: Bitmap? = null
    var night: Boolean = false
    var onTurn: (Int) -> Unit = {}
    var onCenter: () -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val page = RectF()
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x28000000 }
    private val slop = max(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), 6f * resources.displayMetrics.density)
    private var downX = 0f
    private var downY = 0f
    private var offset = 0f
    private var dragging = false
    private var pointer = -1
    private var velocity: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        contentDescription = "单页卡片阅读区，左右拖动翻页"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        page.set(0f, 0f, w.toFloat(), h.toFloat())
        cancelAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (night) Color.rgb(32, 32, 30) else Color.rgb(250, 247, 239))
        val source = current ?: return
        if (offset == 0f) {
            canvas.drawBitmap(source, null, page, paint)
            return
        }
        val direction = if (offset < 0f) 1 else -1
        val target = if (direction > 0) next else previous
        if (target == null) {
            canvas.save()
            canvas.translate(offset * .18f, 0f)
            canvas.drawBitmap(source, null, page, paint)
            canvas.restore()
            return
        }
        val targetX = offset + if (direction > 0) width else -width
        canvas.save()
        canvas.translate(targetX, 0f)
        canvas.drawBitmap(target, null, page, paint)
        canvas.restore()
        canvas.save()
        canvas.translate(offset, 0f)
        canvas.drawBitmap(source, null, page, paint)
        canvas.drawRect(
            if (direction > 0) width - 10f * resources.displayMetrics.density else 0f,
            0f,
            if (direction > 0) width.toFloat() else 10f * resources.displayMetrics.density,
            height.toFloat(),
            shadow,
        )
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (animator != null) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) releasePointer()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                downX = event.x
                downY = event.y
                offset = 0f
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                settle(false)
                releasePointer()
                return true
            }
        }
        val index = event.findPointerIndex(pointer)
        if (index < 0) return true
        val x = event.getX(index)
        val y = event.getY(index)
        val dx = x - downX
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(dx) > slop && abs(dx) > abs(y - downY) * 1.08f) dragging = true
                if (dragging) {
                    val hasTarget = if (dx < 0f) next != null else previous != null
                    offset = if (hasTarget) dx.coerceIn(-width.toFloat(), width.toFloat()) else dx * .18f
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.getXVelocity(pointer) ?: 0f
                if (dragging) {
                    val targetAvailable = if (offset < 0f) next != null else previous != null
                    val commit = targetAvailable && (abs(offset) >= width * .18f || abs(vx) >= 650f * resources.displayMetrics.density)
                    settle(commit)
                } else if (abs(dx) <= slop && abs(y - downY) <= slop) {
                    performClick()
                    when {
                        downX < width * .28f && previous != null -> animateTurn(-1)
                        downX > width * .72f && next != null -> animateTurn(1)
                        else -> onCenter()
                    }
                }
                releasePointer()
            }
        }
        return true
    }

    override fun turnPage(direction: Int): Boolean {
        if (!isEnabled || animator != null || pointer >= 0 || width < 2 || height < 1 ||
            current == null || direction !in listOf(-1,1) ||
            (if(direction > 0) next else previous).let { it == null || it === current }) return false
        animateTurn(direction)
        return true
    }

    private fun animateTurn(direction: Int) {
        offset = if (direction > 0) -1f else 1f
        settle(true)
    }

    private fun settle(commit: Boolean) {
        val direction = if (offset < 0f) 1 else if (offset > 0f) -1 else 0
        if (direction == 0) return
        val end = if (commit) (if (direction > 0) -width.toFloat() else width.toFloat()) else 0f
        val start = offset
        val animation = ValueAnimator.ofFloat(start, end).apply {
            duration = (110 + 150 * (abs(end - start) / width.coerceAtLeast(1))).toLong()
            interpolator = DecelerateInterpolator(1.35f)
            addUpdateListener {
                offset = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    offset = 0f
                    if (commit) onTurn(direction)
                    invalidate()
                }
            })
        }
        // A zero-duration system animation may complete synchronously inside start().
        animator = animation
        animation.start()
    }

    private fun cancelAnimation() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        offset = 0f
    }

    private fun releasePointer() {
        pointer = -1
        velocity?.recycle()
        velocity = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        releasePointer()
        super.onDetachedFromWindow()
    }
}
