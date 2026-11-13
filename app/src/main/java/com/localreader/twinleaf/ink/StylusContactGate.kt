package com.localreader.twinleaf.ink

enum class StylusContactDecision { ACCEPT, HOLD, END }

/** Per gesture gate. Explicit UP, CANCEL and hover must also call end(). No time limit. */
class StylusContactGate(profile: StylusCalibrationProfile? = null) {
    private val calibration = profile?.takeIf { it.isUsable }
    val calibrationEnabled: Boolean get() = calibration != null
    private var ended = false
    private var accepted = false
    private var airborneSamples = 0

    fun begin() { ended = false; accepted = false; airborneSamples = 0 }
    fun end() { ended = true; accepted = false; airborneSamples = 0 }

    fun onSample(rawPressure: Float, rawDistance: Float? = null): StylusContactDecision {
        if (ended) return StylusContactDecision.END
        val profile = calibration ?: return StylusContactDecision.ACCEPT
        val pressureAvailable = profile.validPressure && rawPressure.isFinite() && rawPressure >= 0f
        val distanceAvailable = profile.validDistance && rawDistance != null && rawDistance.isFinite() && rawDistance >= 0f
        // Missing hardware axes fall back to explicit event boundaries, including zero-pressure devices.
        if (!pressureAvailable && !distanceAvailable) {
            airborneSamples = 0
            return StylusContactDecision.ACCEPT
        }
        val pressureAir = pressureAvailable && rawPressure <= profile.pressureEnd!!
        val distanceAir = distanceAvailable && rawDistance!! >= profile.distanceEnd!!
        // Require agreement when both calibrated axes are available; protect legitimate light contact.
        val airborne = (!pressureAvailable || pressureAir) && (!distanceAvailable || distanceAir)
        if (airborne) {
            // Some drivers issue DOWN before the nib develops measurable pressure.
            // Wait for first contact; there is no existing stroke to finish yet.
            if (!accepted) return StylusContactDecision.HOLD
            airborneSamples++
            if (airborneSamples >= 3) { end(); return StylusContactDecision.END }
            return StylusContactDecision.HOLD
        }
        airborneSamples = 0
        val pressureContact = pressureAvailable && rawPressure >= profile.pressureStart!!
        val distanceContact = distanceAvailable && rawDistance!! <= profile.distanceStart!!
        if (accepted || pressureContact || distanceContact) {
            accepted = true
            return StylusContactDecision.ACCEPT
        }
        return StylusContactDecision.HOLD
    }
}
