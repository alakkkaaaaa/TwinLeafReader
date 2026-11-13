package com.localreader.twinleaf.ui.theme

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import android.widget.Toast

enum class ThemeMode(val label:String) { SYSTEM("跟随系统"), LIGHT("始终浅色"), DARK("始终深色") }
fun ThemeMode.next(): ThemeMode = ThemeMode.entries[(ordinal + 1) % ThemeMode.entries.size]
val LocalReaderDark = staticCompositionLocalOf { false }

@Composable
fun rememberThemeMode(): ThemeMode {
    val context=LocalContext.current
    val prefs=remember(context) { context.getSharedPreferences("reader_visual_settings",Context.MODE_PRIVATE) }
    fun read()=runCatching { ThemeMode.valueOf(prefs.getString("themeMode","SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
    var mode by remember { mutableStateOf(read()) }
    DisposableEffect(prefs) {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if(key=="themeMode") mode=read() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return mode
}

fun setThemeMode(context:Context,mode:ThemeMode) {
    context.getSharedPreferences("reader_visual_settings",Context.MODE_PRIVATE).edit().putString("themeMode",mode.name).apply()
}

fun cycleThemeMode(context: Context): ThemeMode {
    val prefs=context.getSharedPreferences("reader_visual_settings",Context.MODE_PRIVATE)
    val current=runCatching { ThemeMode.valueOf(prefs.getString("themeMode","SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
    return current.next().also { setThemeMode(context,it) }
}

@Composable
fun ThemeCycleButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mode = rememberThemeMode()
    var feedback by remember { mutableStateOf<Toast?>(null) }
    DisposableEffect(Unit) { onDispose { feedback?.cancel() } }
    IconButton(onClick = {
        val next = cycleThemeMode(context)
        feedback?.cancel()
        feedback = Toast.makeText(context, next.label, Toast.LENGTH_SHORT).also { it.show() }
    }, modifier = modifier.semantics { stateDescription = mode.label }) {
        Icon(when (mode) {
            ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
            ThemeMode.LIGHT -> Icons.Rounded.LightMode
            ThemeMode.DARK -> Icons.Rounded.DarkMode
        }, "切换亮暗模式", tint = MaterialTheme.colorScheme.primary)
    }
}
