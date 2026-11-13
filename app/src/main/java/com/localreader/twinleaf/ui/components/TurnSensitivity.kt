package com.localreader.twinleaf.ui.components

import kotlin.math.min

/** Distances and velocity use pixels; density keeps flicks consistent across tablets. */
internal object TurnSensitivity {
    fun progress(distance: Float, width: Int): Float =
        (distance / (width.coerceAtLeast(1) * 0.65f)).coerceIn(0f, 0.98f)

    fun shouldCommit(distance: Float, width: Int, density: Float, velocity: Float): Boolean {
        if (velocity < -450f * density) return false // A deliberate flick back cancels.
        return distance >= min(width * 0.06f, 72f * density) ||
            (distance >= 12f * density && velocity >= 650f * density)
    }
}
