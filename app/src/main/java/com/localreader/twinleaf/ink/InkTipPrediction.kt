package com.localreader.twinleaf.ink

import kotlin.math.hypot

/** Display-only, conservative prediction; it never appends to document samples. */
internal object InkTipPrediction {
    const val MAX_AGE_MS = 24L
    const val MAX_LEAD_MS = 8f

    fun predict(points: List<InkPoint>, now: Long, scaleX: Float, scaleY: Float,
                maxScreenDistance: Float, refreshRate: Float): InkPoint? {
        if (points.size < 3 || !scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0f || scaleY <= 0f ||
            !maxScreenDistance.isFinite() || maxScreenDistance <= 0f) return null
        val a = points[points.size - 3]
        val b = points[points.size - 2]
        val c = points.last()
        val age = now - c.timeMillis
        val dt1 = b.timeMillis - a.timeMillis
        val dt2 = c.timeMillis - b.timeMillis
        if (age !in 0..MAX_AGE_MS || dt1 !in 1..32 || dt2 !in 1..32) return null
        val ax = (b.x - a.x) * scaleX
        val ay = (b.y - a.y) * scaleY
        val bx = (c.x - b.x) * scaleX
        val by = (c.y - b.y) * scaleY
        val lengthA = hypot(ax, ay)
        val lengthB = hypot(bx, by)
        if (!lengthA.isFinite() || !lengthB.isFinite() || lengthA < .5f || lengthB < .5f) return null
        // Corners, reversals, sudden acceleration and pressure collapse must not grow a tail.
        if ((ax * bx + ay * by) / (lengthA * lengthB) < .92f) return null
        val speedA = lengthA / dt1
        val speedB = lengthB / dt2
        if (speedB < speedA * .4f || speedB > speedA * 2f) return null
        if (!c.rawPressure.isFinite() || c.rawPressure < 0f ||
            (b.rawPressure > 0f && c.rawPressure < b.rawPressure * .55f)) return null
        val hz = refreshRate.takeIf { it.isFinite() && it > 0f } ?: 60f
        val horizon = (minOf(MAX_LEAD_MS, 1000f / hz) + age).coerceAtMost(12f)
        val distance = minOf(maxScreenDistance, speedB * horizon)
        return c.copy(x = c.x + bx / lengthB * distance / scaleX,
            y = c.y + by / lengthB * distance / scaleY, timeMillis = c.timeMillis + horizon.toLong())
    }
}
