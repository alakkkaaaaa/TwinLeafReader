package com.localreader.twinleaf.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF241F1A)
private val Parchment = Color(0xFFF1E8D8)
private val Walnut = Color(0xFF6E4930)
private val Night = Color(0xFF181512)

private val TwinLeafColors = lightColorScheme(
    primary = Color(0xFF5D78B9),
    onPrimary = Color.White,
    secondary = Color(0xFF7F92BC),
    background = Color(0xFFF1F5FC),
    onBackground = Color(0xFF243451),
    surface = Color(0xFFFAFCFF),
    onSurface = Color(0xFF243451),
    surfaceVariant = Color(0xFFE4EBF8),
    onSurfaceVariant = Color(0xFF52617B),
    error = Color(0xFFBA4354),
)

@Composable
fun TwinLeafTheme(content: @Composable () -> Unit) {
    val dark=when(rememberThemeMode()) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val view=LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity -> WindowCompat.getInsetsController(activity.window,view).apply {
            isAppearanceLightStatusBars=!dark; isAppearanceLightNavigationBars=!dark
        } }
    }
    CompositionLocalProvider(LocalReaderDark provides dark) {
        MaterialTheme(colorScheme = if(dark) darkColorScheme(primary=Color(0xFFAEC4FA),onPrimary=Color(0xFF203452),
            background=Color(0xFF141B27),onBackground=Color(0xFFE0E7F5),surface=Color(0xFF202A3A),
            onSurface=Color(0xFFE0E7F5),surfaceVariant=Color(0xFF2D3B50),onSurfaceVariant=Color(0xFFAFBBD0)) else TwinLeafColors, content = content)
    }
}
