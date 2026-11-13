package com.localreader.twinleaf.ink

import android.content.Context
import java.security.MessageDigest

/** Versioned private preferences, keyed by persistent hardware descriptor and input source. */
class StylusCalibrationRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("stylus_calibration_v1", Context.MODE_PRIVATE)

    private fun storageKey(key: StylusDeviceKey): String = MessageDigest.getInstance("SHA-256")
        .digest("${key.source}:${key.descriptor}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun load(key: StylusDeviceKey): StylusCalibrationProfile? {
        val saved = preferences.getString(storageKey(key), null) ?: return null
        val fields = saved.split('|')
        if (fields.size != 6 || fields[0] != "1") return null
        fun axis(index: Int): Float? = fields[index].toFloatOrNull()
        return StylusCalibrationProfile(key, axis(1), axis(2), axis(3), axis(4), axis(5) ?: return null)
            .takeIf { it.isUsable }
    }

    fun save(profile: StylusCalibrationProfile) {
        require(profile.isUsable) { "Cannot persist an unreliable stylus calibration" }
        val saved = listOf("1", profile.pressureStart ?: "", profile.pressureEnd ?: "",
            profile.distanceStart ?: "", profile.distanceEnd ?: "", profile.confidence).joinToString("|")
        preferences.edit().putString(storageKey(profile.key), saved).apply()
    }

    fun reset(key: StylusDeviceKey) { preferences.edit().remove(storageKey(key)).apply() }
}
