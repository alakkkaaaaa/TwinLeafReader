package com.localreader.twinleaf.data

import android.content.Context
import java.security.MessageDigest

/** Navigation used while the device is held in portrait. */
enum class PortraitPageMode {
    SCROLL,
    CARD;

    fun next(): PortraitPageMode = entries[(ordinal + 1) % entries.size]
}

/** Page grouping used while the device is held in landscape. */
enum class LandscapePageMode {
    AUTO,
    SINGLE,
    DOUBLE;

    fun next(): LandscapePageMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Stores layout choices per book.  The raw document URI is deliberately hashed before it is
 * used as a preference key: persisted SAF URIs can contain provider-specific identifiers and
 * may be longer than is comfortable for SharedPreferences XML attribute names.
 */
class ReadingLayoutPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "reading_layout_preferences_v3",
        Context.MODE_PRIVATE,
    )

    fun portraitMode(bookKey: String): PortraitPageMode = enumValue(
        key = "portrait_${stableKey(bookKey)}",
        fallback = PortraitPageMode.SCROLL,
    )

    fun landscapeMode(bookKey: String): LandscapePageMode = enumValue(
        key = "landscape_${stableKey(bookKey)}",
        fallback = LandscapePageMode.AUTO,
    )

    fun setPortraitMode(bookKey: String, mode: PortraitPageMode) {
        preferences.edit().putString("portrait_${stableKey(bookKey)}", mode.name).apply()
    }

    fun setLandscapeMode(bookKey: String, mode: LandscapePageMode) {
        preferences.edit().putString("landscape_${stableKey(bookKey)}", mode.name).apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        preferences.getString(key, null)
            ?.let { saved -> enumValues<T>().firstOrNull { it.name == saved } }
            ?: fallback

    private fun stableKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
    }
}
