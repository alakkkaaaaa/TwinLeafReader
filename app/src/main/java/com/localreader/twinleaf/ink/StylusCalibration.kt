package com.localreader.twinleaf.ink

data class StylusDeviceKey(val descriptor: String, val source: Int) {
    companion object {
        fun from(device: android.view.InputDevice?, source: Int): StylusDeviceKey? =
            device?.descriptor?.takeIf { it.isNotBlank() }?.let { StylusDeviceKey(it, source) }
    }
}

data class StylusCapabilities(val pressure: Boolean, val distance: Boolean) {
    companion object {
        fun from(device: android.view.InputDevice?, source: Int): StylusCapabilities {
            fun supported(axis: Int): Boolean = device?.getMotionRange(axis, source)?.let {
                it.min.isFinite() && it.max.isFinite() && it.max > it.min
            } ?: false
            return StylusCapabilities(supported(android.view.MotionEvent.AXIS_PRESSURE),
                supported(android.view.MotionEvent.AXIS_DISTANCE))
        }
    }
}

enum class StylusCalibrationPhase { HOVER, LIGHT_WRITING, LIFT }

/** Values are raw MotionEvent axes, never rendering pressure or smoothed values. */
data class StylusCalibrationSample(
    val phase: StylusCalibrationPhase,
    val pressure: Float,
    val distance: Float? = null,
)

data class StylusCalibrationProfile(
    val key: StylusDeviceKey,
    val pressureStart: Float? = null,
    val pressureEnd: Float? = null,
    val distanceStart: Float? = null,
    val distanceEnd: Float? = null,
    val confidence: Float,
) {
    val isUsable: Boolean get() = key.descriptor.isNotBlank() && confidence.isFinite() && confidence >= 0.6f &&
        (validPressure || validDistance)
    internal val validPressure: Boolean get() = pressureStart != null && pressureEnd != null &&
        pressureStart.isFinite() && pressureEnd.isFinite() && pressureEnd >= 0f && pressureStart > pressureEnd
    internal val validDistance: Boolean get() = distanceStart != null && distanceEnd != null &&
        distanceStart.isFinite() && distanceEnd.isFinite() && distanceStart >= 0f && distanceEnd > distanceStart
}

data class StylusCalibrationResult(val profile: StylusCalibrationProfile?, val message: String)

object StylusCalibration {
    /** Conservative full-range separation protects the lightest observed writing samples. */
    fun calibrate(
        key: StylusDeviceKey,
        capabilities: StylusCapabilities,
        samples: List<StylusCalibrationSample>,
    ): StylusCalibrationResult {
        if (key.descriptor.isBlank()) return StylusCalibrationResult(null, "无法识别稳定的手写笔设备，请重新连接后重试。")
        val groups = StylusCalibrationPhase.entries.associateWith { phase -> samples.filter { it.phase == phase } }
        if (groups.values.any { it.size < 12 }) return StylusCalibrationResult(null, "每一步至少需要 12 个采样点，请重新完成校准。")
        val contact = groups.getValue(StylusCalibrationPhase.LIGHT_WRITING)
        val airborne = groups.getValue(StylusCalibrationPhase.HOVER) + groups.getValue(StylusCalibrationPhase.LIFT)
        fun separation(axis: (StylusCalibrationSample) -> Float?, reversed: Boolean): Pair<Float, Float>? {
            val writing = contact.mapNotNull(axis).filter { it.isFinite() && it >= 0f }
            val hover = airborne.mapNotNull(axis).filter { it.isFinite() && it >= 0f }
            // Missing or malformed axes cannot establish reliable separation.
            if (writing.size != contact.size || hover.size != airborne.size) return null
            val low = if (reversed) writing.max() else hover.max()
            val high = if (reversed) hover.min() else writing.min()
            val gap = high - low
            if (gap <= maxOf(0.0001f, maxOf(high, low) * 0.05f)) return null
            return (low + gap * 0.35f) to (low + gap * 0.65f)
        }
        val pressure = if (capabilities.pressure) separation({ it.pressure }, false) else null
        val distance = if (capabilities.distance) separation({ it.distance }, true) else null
        if (pressure == null && distance == null) return StylusCalibrationResult(null,
            "悬空、轻写与抬笔数据无法可靠区分，已保留事件判定；请尽量轻写后重新校准。")
        val profile = StylusCalibrationProfile(key,
            pressureStart = pressure?.second, pressureEnd = pressure?.first,
            distanceStart = distance?.first, distanceEnd = distance?.second,
            confidence = if (pressure != null && distance != null) 0.95f else 0.8f)
        return StylusCalibrationResult(profile, "校准完成，已保留轻写范围并启用抬笔识别。")
    }
}
