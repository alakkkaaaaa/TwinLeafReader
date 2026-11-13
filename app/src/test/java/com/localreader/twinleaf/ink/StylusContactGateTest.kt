package com.localreader.twinleaf.ink

import org.junit.Assert.*
import org.junit.Test

class StylusContactGateTest {
    private val profile = StylusCalibrationProfile(StylusDeviceKey("pen", 1), pressureStart = 0.01f,
        pressureEnd = 0.005f, confidence = 0.8f)

    @Test fun uncalibratedZeroPressureStillWrites() {
        val gate = StylusContactGate()
        assertFalse(gate.calibrationEnabled)
        repeat(100) { assertEquals(StylusContactDecision.ACCEPT, gate.onSample(0f)) }
    }

    @Test fun confirmedLiftCannotBridgeIntoLaterMove() {
        val gate = StylusContactGate(profile)
        assertEquals(StylusContactDecision.ACCEPT, gate.onSample(0.02f))
        repeat(2) { assertEquals(StylusContactDecision.HOLD, gate.onSample(0f)) }
        assertEquals(StylusContactDecision.END, gate.onSample(0f))
        assertEquals(StylusContactDecision.END, gate.onSample(0.5f))
        gate.begin()
        assertEquals(StylusContactDecision.ACCEPT, gate.onSample(0.02f))
    }

    @Test fun briefDipAndLongLightStrokeArePreserved() {
        val gate = StylusContactGate(profile)
        assertEquals(StylusContactDecision.ACCEPT, gate.onSample(0.02f))
        assertEquals(StylusContactDecision.HOLD, gate.onSample(0f))
        repeat(10000) { assertEquals(StylusContactDecision.ACCEPT, gate.onSample(0.007f)) }
        assertEquals(StylusContactDecision.HOLD, gate.onSample(0f))
        assertEquals(StylusContactDecision.ACCEPT, gate.onSample(0.02f))
    }

    @Test fun explicitEndAlsoStopsUnknownDevice() {
        val gate = StylusContactGate()
        gate.end()
        assertEquals(StylusContactDecision.END, gate.onSample(1f))
    }

    @Test fun slowTouchdownCanBuildPressureBeforeFirstContact() {
        val gate = StylusContactGate(profile)
        gate.begin()
        repeat(20) { assertEquals(StylusContactDecision.HOLD, gate.onSample(0f)) }
        assertEquals(StylusContactDecision.ACCEPT, gate.onSample(0.015f))
        repeat(2) { assertEquals(StylusContactDecision.HOLD, gate.onSample(0f)) }
        assertEquals(StylusContactDecision.END, gate.onSample(0f))
    }

    @Test fun disagreeingDistanceDoesNotDiscardLightContact() {
        val gate = StylusContactGate(profile.copy(distanceStart = 0.2f, distanceEnd = 0.5f))
        repeat(20) { assertEquals(StylusContactDecision.ACCEPT, gate.onSample(0f, 0f)) }
        repeat(2) { assertEquals(StylusContactDecision.HOLD, gate.onSample(0f, 1f)) }
        assertEquals(StylusContactDecision.END, gate.onSample(0f, 1f))
    }

    @Test fun invalidProfileFallsBackToEvents() {
        assertFalse(StylusContactGate(profile.copy(confidence = Float.NaN)).calibrationEnabled)
        assertFalse(StylusContactGate(profile.copy(pressureEnd = 0.5f)).calibrationEnabled)
    }
}
