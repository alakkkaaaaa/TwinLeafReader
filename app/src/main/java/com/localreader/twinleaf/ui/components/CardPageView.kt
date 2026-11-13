package com.localreader.twinleaf.ui.components

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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A lightweight, allocation-free page-card transition used for single PDF pages and transitions
 * next to them. The current texture follows the finger; the incoming card remains visible below
 * it with a small scale/depth change instead of imitating a sheet of paper.
 */
class CardPageView(context: Context) : View(context), PageTurnTarget {
    var current: Bitmap? = null
        set(value) {
            if (field !== value && animator != null) abort()
            field = value
        }
    var previous: Bitmap? = null
    var next: Bitmap? = null
    var night: Boolean = false
    var onTurn: (Int) -> Unit = {}
    var onCenterTap: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val touchSlop = max(ViewConfiguration.get(context).scaledTouchSlop * .7f, 4f * density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val destination = RectF()
    private var pointer = -1
    private var downX = 0f
    private var downY = 0f
    private var direction = 0
    private var progress = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null
    private var sourceFrame: Bitmap? = null
    private var targetFrame: Bitmap? = null

    init {
        isClickable = true
        contentDescription = "单页卡片阅读区，左右拖动换页"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (night) Color.rgb(32, 32, 30) else Color.rgb(245, 241, 232))
        val source = sourceFrame ?: current ?: return
        val target = targetFrame
        if (direction == 0 || target == null || progress <= 0f) {
            drawFrame(canvas, source, 0f, 1f)
            return
        }

        // Incoming page stays almost in place like a physical card underneath.
        val incomingOffset = if (direction > 0) width * (.10f - .10f * progress)
            else -width * (.10f - .10f * progress)
        drawFrame(canvas, target, incomingOffset, .965f + .035f * progress)

        val sourceOffset = if (direction > 0) -width * progress else width * progress
        shadowPaint.color = Color.argb((44 * (1f - progress)).toInt(), 0, 0, 0)
        val shadowLeft = if (direction > 0) sourceOffset + width - 18f * density else sourceOffset
        canvas.drawRect(shadowLeft, 0f, shadowLeft + 18f * density, height.toFloat(), shadowPaint)
        drawFrame(canvas, source, sourceOffset, 1f)
    }

    private fun drawFrame(canvas: Canvas, bitmap: Bitmap, offsetX: Float, scale: Float) {
        val scaledWidth = width * scale
        val scaledHeight = height * scale
        val left = offsetX + (width - scaledWidth) / 2f
        val top = (height - scaledHeight) / 2f
        destination.set(left, top, left + scaledWidth, top + scaledHeight)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    override fun turnPage(direction: Int): Boolean {
        if (!isEnabled || pointer >= 0 || width < 2 || height < 1 ||
            direction !in listOf(-1,1) || !begin(direction)) return false
        settle(true)
        return true
    }

    private fun begin(dir: Int): Boolean {
        val source = current ?: return false
        val target = if (dir > 0) next else previous
        if (target == null || target === source || animator != null) return false
        direction = dir
        sourceFrame = source
        targetFrame = target
        return true
    }

    private fun settle(commit: Boolean) {
        if (direction == 0 || animator != null) return
        val dir = direction
        val targetProgress = if (commit) 1f else 0f
        val animation = ValueAnimator.ofFloat(progress, targetProgress).apply {
            duration = (90 + 170 * abs(targetProgress - progress)).toLong()
            interpolator = DecelerateInterpolator(1.35f)
            addUpdateListener {
                progress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    val target = targetFrame
                    clearAnimationState()
                    if (commit && target != null) {
                        current = target
                        onTurn(dir)
                    }
                    invalidate()
                }
            })
        }
        // Assign before start: when system animations are disabled, end may run immediately.
        animator = animation
        animation.start()
    }

    private fun clearAnimationState() {
        animator = null
        direction = 0
        progress = 0f
        dragging = false
        sourceFrame = null
        targetFrame = null
    }

    private fun abort() {
        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        clearAnimationState()
        releasePointer()
    }

    private fun releasePointer() {
        pointer = -1
        tracker?.recycle()
        tracker = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) abort()
        super.setEnabled(enabled)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (animator != null) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) releasePointer()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                downX = event.x
                downY = event.y
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
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
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(y - downY) * 1.1f) {
                    dragging = begin(if (dx < 0f) 1 else -1)
                }
                if (dragging) {
                    progress = (abs(dx) / width.coerceAtLeast(1)).coerceIn(0f, .98f)
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_UP -> {
                tracker?.computeCurrentVelocity(1000)
                if (dragging) {
                    val forwardVelocity = abs(tracker?.getXVelocity(pointer) ?: 0f)
                    val threshold = min(width * .18f, 136f * density)
                    val commit = abs(dx) >= threshold ||
                        (abs(dx) >= 18f * density && forwardVelocity >= 650f * density)
                    settle(commit)
                } else if (abs(dx) <= touchSlop && abs(y - downY) <= touchSlop) {
                    performClick()
                    when {
                        downX < width * .35f && begin(-1) -> settle(true)
                        downX > width * .65f && begin(1) -> settle(true)
                        else -> onCenterTap()
                    }
                }
                releasePointer()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        abort()
        super.onDetachedFromWindow()
    }
}
