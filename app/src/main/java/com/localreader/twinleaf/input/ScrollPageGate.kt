package com.localreader.twinleaf.input

import kotlin.math.abs

/** No DOWN/UP or pointer capture is required for a mouse wheel / touchpad scroll stream. */
internal class ScrollPageGate(private val threshold: Float, private val intervalMs: Long = 220) {
    private var lastEvent = Long.MIN_VALUE
    private var lastAttempt = Long.MIN_VALUE
    private var axis = 0
    private var sum = 0f

    fun reset() {
        lastEvent = Long.MIN_VALUE; lastAttempt = Long.MIN_VALUE; axis = 0; sum = 0f
    }

    fun scroll(x: Float, y: Float, time: Long, turn: (Int) -> Boolean) {
        if (!x.isFinite() || !y.isFinite() || (x == 0f && y == 0f)) return
        val nextAxis = if (abs(x) > abs(y)) 1 else 2
        val delta = if (nextAxis == 1) x else y
        val idle = lastEvent == Long.MIN_VALUE || time < lastEvent || time - lastEvent > 180
        if (idle || axis != nextAxis || sum * delta < 0f) sum = 0f
        if (idle) lastAttempt = Long.MIN_VALUE
        axis = nextAxis; lastEvent = time
        sum = (sum + delta).coerceIn(-threshold * 2, threshold * 2)
        if (abs(sum) < threshold ||
            (lastAttempt != Long.MIN_VALUE && time - lastAttempt < intervalMs)) return
        val direction = if (sum > 0f) 1 else -1
        // Busy animations and unloaded neighbors never queue up a delayed burst of turns.
        sum = 0f; lastAttempt = time
        turn(direction)
    }
}
