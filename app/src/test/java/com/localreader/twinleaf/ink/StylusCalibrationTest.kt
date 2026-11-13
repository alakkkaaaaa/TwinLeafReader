package com.localreader.twinleaf.ink

import org.junit.Assert.*
import org.junit.Test

class StylusCalibrationTest {
    private val key = StylusDeviceKey("stable-device", 16386)
    private fun samples(hover: Float = 0f, light: Float = 0.015f, lift: Float = 0f) =
        StylusCalibrationPhase.entries.flatMap { phase -> List(12) {
            StylusCalibrationSample(phase, when (phase) {
                StylusCalibrationPhase.HOVER -> hover
                StylusCalibrationPhase.LIGHT_WRITING -> light
                StylusCalibrationPhase.LIFT -> lift
            })
        } }

    @Test fun thresholdsPreserveVeryLightWriting() {
        val profile = StylusCalibration.calibrate(key, StylusCapabilities(true, false), samples()).profile!!
        assertTrue(profile.pressureStart!! < 0.015f)
        assertTrue(profile.pressureEnd!! < profile.pressureStart!!)
        assertEquals(key, profile.key)
    }

    @Test fun overlapAndStuckPressureAreRejected() {
        assertNull(StylusCalibration.calibrate(key, StylusCapabilities(true, false), samples(0.02f, 0.015f)).profile)
        assertNull(StylusCalibration.calibrate(key, StylusCapabilities(true, false), samples(0f, 0f)).profile)
        assertNull(StylusCalibration.calibrate(key, StylusCapabilities(true, false), samples(lift = 0.02f)).profile)
    }

    @Test fun allThreePhasesAndValidSamplesAreRequired() {
        assertNull(StylusCalibration.calibrate(key, StylusCapabilities(true, false), samples().drop(1)).profile)
        val invalid = samples().toMutableList().apply { this[15] = this[15].copy(pressure = Float.NaN) }
        assertNull(StylusCalibration.calibrate(key, StylusCapabilities(true, false), invalid).profile)
    }

    @Test fun distanceCanCalibrateDeviceWithNoPressureAxis() {
        val data = samples(0f, 0f).map { it.copy(distance = if (it.phase == StylusCalibrationPhase.LIGHT_WRITING) 0f else 2f) }
        val profile = StylusCalibration.calibrate(key, StylusCapabilities(false, true), data).profile!!
        assertNull(profile.pressureStart)
        assertTrue(profile.distanceStart!! < profile.distanceEnd!!)
        assertNull(StylusCalibration.calibrate(key, StylusCapabilities(false, false), data).profile)
    }
}
