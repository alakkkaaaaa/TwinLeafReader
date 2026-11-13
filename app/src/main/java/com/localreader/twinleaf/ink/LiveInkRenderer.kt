package com.localreader.twinleaf.ink

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Display-only cache. The live list is owned by the view thread; only finish() copies it
 * into the immutable document. Sealed display lists are recorded once, not once per MOVE.
 * No vendor binaries, hidden services or rasterized source data are involved.
 */
internal class LiveInkRenderer(ovalBrushDot: Boolean = true) {
    private val painter = InkStrokePainter(ovalBrushDot)
    private var stroke: InkStroke? = null
    private var rect: RectF? = null
    private var chunks: HardwareChunks? = null
    private val highlightPath = Path()
    private var highlightPoints = 0
    private val predictionPoints = ArrayList<InkPoint>(2)
    private var predictionStroke: InkStroke? = null
    var predictionAllowed = true
    var recordedSegments = 0L; private set

    fun begin(value: InkStroke, pageRect: RectF? = null) {
        clear()
        stroke = value
        rect = pageRect?.let(::RectF)
        predictionStroke = value.copy(points = predictionPoints)
        predictionAllowed = true
    }

    /** True requests one later invalidation to remove a tip after input becomes stationary. */
    fun draw(canvas: Canvas, scale: Float = 1f, maxPredictionPixels: Float = 12f,
             refreshRate: Float = 60f): Boolean {
        val current = stroke ?: return false
        if (current.points.isEmpty()) return false
        val page = rect
        canvas.save()
        if (page != null) canvas.clipRect(page)
        if (current.highlight || current.brush == InkBrush.HIGHLIGHTER) {
            // One path avoids double alpha at chunk boundaries and preserves crossings.
            val sx = page?.width() ?: 1f
            val sy = page?.height() ?: 1f
            val left = page?.left ?: 0f
            val top = page?.top ?: 0f
            if (current.points.size == 1) painter.draw(canvas, current, page)
            else {
                while (highlightPoints < current.points.size) {
                    val point = current.points[highlightPoints]
                    if (highlightPoints == 0) highlightPath.moveTo(left + point.x * sx, top + point.y * sy)
                    else highlightPath.lineTo(left + point.x * sx, top + point.y * sy)
                    highlightPoints++
                }
                painter.drawHighlight(canvas, current, highlightPath, sx)
            }
        } else if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated) {
            val cache = chunks ?: HardwareChunks(painter) { recordedSegments += it }.also { chunks = it }
            cache.draw(canvas, current, page)
        } else painter.draw(canvas, current, page)
        val predicted = if (predictionAllowed && !current.highlight && current.brush != InkBrush.HIGHLIGHTER)
            InkTipPrediction.predict(current.points, SystemClock.uptimeMillis(),
                (page?.width() ?: 1f) * scale, (page?.height() ?: 1f) * scale,
                maxPredictionPixels, refreshRate) else null
        if (predicted != null) {
            predictionPoints.clear()
            predictionPoints.add(current.points.last())
            predictionPoints.add(if (page == null) predicted else
                predicted.copy(x = predicted.x.coerceIn(0f, 1f), y = predicted.y.coerceIn(0f, 1f)))
            painter.draw(canvas, predictionStroke!!, page)
        }
        canvas.restore()
        return predicted != null
    }

    fun clear() {
        if (Build.VERSION.SDK_INT >= 29) chunks?.clear()
        chunks = null
        stroke = null
        rect = null
        highlightPath.rewind()
        highlightPoints = 0
        predictionPoints.clear()
        predictionStroke = null
        recordedSegments = 0L
    }

    @RequiresApi(29)
    private class HardwareChunks(private val painter: InkStrokePainter, private val onRecord: (Int) -> Unit) {
        private val nodes = ArrayList<RenderNode>()
        private val lastRecorded = ArrayList<Int>()
        fun draw(canvas: Canvas, stroke: InkStroke, rect: RectF?) {
            val ranges = LiveStrokeChunks.count(stroke.points.size)
            for (chunk in 0 until ranges) {
                val first = chunk * LiveStrokeChunks.SEGMENTS
                val last = minOf(first + LiveStrokeChunks.SEGMENTS, stroke.points.lastIndex)
                val node = if (chunk < nodes.size) nodes[chunk] else
                    RenderNode("live-ink-$chunk").apply {
                        setClipToBounds(false)
                        nodes.add(this)
                        lastRecorded.add(-1)
                    }
                if (lastRecorded[chunk] != last) {
                    record(node, stroke, rect, first, last)
                    lastRecorded[chunk] = last
                }
                canvas.drawRenderNode(node)
            }
        }

        private fun record(node: RenderNode, stroke: InkStroke, rect: RectF?, first: Int, last: Int) {
            val sx = rect?.width() ?: 1f
            val sy = rect?.height() ?: 1f
            val originX = rect?.left ?: 0f
            val originY = rect?.top ?: 0f
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            val pad = stroke.width * sx * 1.6f + 2f
            for (i in first..last) {
                val p = stroke.points[i]
                val x = originX + p.x * sx
                val y = originY + p.y * sy
                minX = minOf(minX, x); minY = minOf(minY, y)
                maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
            }
            val left = floor(minX - pad).toInt()
            val top = floor(minY - pad).toInt()
            val right = ceil(maxX + pad).toInt().coerceAtLeast(left + 1)
            val bottom = ceil(maxY + pad).toInt().coerceAtLeast(top + 1)
            node.setPosition(left, top, right, bottom)
            val target = node.beginRecording(right - left, bottom - top)
            try {
                target.translate(-left.toFloat(), -top.toFloat())
                painter.draw(target, stroke, rect, first, last)
                onRecord(maxOf(1,last-first))
            } finally { node.endRecording() }
        }

        fun clear() {
            nodes.forEach { it.discardDisplayList() }
            nodes.clear(); lastRecorded.clear()
        }
    }
}
