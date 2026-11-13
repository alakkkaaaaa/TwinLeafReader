package com.localreader.twinleaf.ink

import android.content.Context
import android.view.MotionEvent

/** Loads once per DOWN so calibration changes apply to the next stroke, not mid-stroke. */
internal class StylusContactSession(context: Context) {
    private val repository = StylusCalibrationRepository(context)
    private var gate = StylusContactGate()
    private var hasDistance = false
    val calibrated: Boolean get() = gate.calibrationEnabled

    fun begin(event: MotionEvent) {
        val key = StylusDeviceKey.from(event.device, event.source)
        gate = StylusContactGate(key?.let(repository::load))
        hasDistance = StylusCapabilities.from(event.device, event.source).distance
        gate.begin()
    }

    fun distance(event: MotionEvent, index: Int, history: Int? = null): Float? =
        if (!hasDistance) null else if (history == null) event.getAxisValue(MotionEvent.AXIS_DISTANCE, index)
        else event.getHistoricalAxisValue(MotionEvent.AXIS_DISTANCE, index, history)

    fun sample(pressure: Float, distance: Float?): StylusContactDecision = gate.onSample(pressure, distance)
    fun end() = gate.end()
}
