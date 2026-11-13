package com.localreader.twinleaf.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Reused by a canvas: no Paint, Path or point-list allocation for each stroke/frame. */
internal class InkStrokePainter(private val ovalBrushDot: Boolean = true) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    fun draw(canvas: Canvas, stroke: InkStroke, rect: RectF? = null,
             first: Int = 0, last: Int = stroke.points.lastIndex) {
        if (last < first || first < 0 || last >= stroke.points.size) return
        val sx = rect?.width() ?: 1f
        val sy = rect?.height() ?: 1f
        val left = rect?.left ?: 0f
        val top = rect?.top ?: 0f
        paint.color = stroke.color
        paint.alpha = (inkOpacity(stroke, stroke.points[first]) * 255).toInt().coerceIn(0, 255)
        paint.style = Paint.Style.STROKE
        if (stroke.highlight || stroke.brush == InkBrush.HIGHLIGHTER) {
            path.rewind()
            path.moveTo(left + stroke.points[first].x * sx, top + stroke.points[first].y * sy)
            if (last == first) path.rLineTo(.001f, 0f)
            for (i in first + 1..last) {
                val point = stroke.points[i]
                path.lineTo(left + point.x * sx, top + point.y * sy)
            }
            drawHighlight(canvas, stroke, path, sx)
        } else if (last == first) {
            val point = stroke.points[first]
            val x = left + point.x * sx
            val y = top + point.y * sy
            val radius = pressureWidth(stroke, point) * sx / 2f
            paint.style = Paint.Style.FILL
            if (ovalBrushDot && stroke.brush == InkBrush.BRUSH) {
                canvas.save()
                canvas.rotate(Math.toDegrees(point.orientation.toDouble()).toFloat(), x, y)
                canvas.drawOval(x - radius, y - radius * .55f, x + radius, y + radius * .55f, paint)
                canvas.restore()
            } else canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.STROKE
        } else {
            for (i in first + 1..last) {
                val a = stroke.points[i - 1]
                val b = stroke.points[i]
                paint.alpha = (inkOpacity(stroke, b) * 255).toInt().coerceIn(0, 255)
                paint.strokeWidth = pressureWidth(stroke, b) * sx
                canvas.drawLine(left + a.x * sx, top + a.y * sy,
                    left + b.x * sx, top + b.y * sy, paint)
            }
        }
    }

    fun drawHighlight(canvas: Canvas, stroke: InkStroke, highlightPath: Path, scaleX: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = stroke.color
        paint.alpha = (stroke.opacity.coerceIn(0f, 1f) * 255).toInt()
        paint.strokeWidth = stroke.width * scaleX
        canvas.drawPath(highlightPath, paint)
    }
}
