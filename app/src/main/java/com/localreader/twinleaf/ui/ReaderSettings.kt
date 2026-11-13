package com.localreader.twinleaf.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.LastPage
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.SwapHoriz
import com.localreader.twinleaf.data.LandscapePageMode
import com.localreader.twinleaf.data.PortraitPageMode

class ReaderVisualSettings internal constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("reader_visual_settings", Context.MODE_PRIVATE)

    var brightness by mutableFloatStateOf(preferences.getFloat("brightness", 0.88f))
        private set
    var nightMode by mutableStateOf(false)
        internal set
    var fontScale by mutableFloatStateOf(preferences.getFloat("fontScale", 1f))
        private set

    fun updateBrightness(value: Float) {
        brightness = value.coerceIn(0.15f, 1f)
        preferences.edit().putFloat("brightness", brightness).apply()
    }

    fun updateFontScale(value: Float) {
        fontScale = value.coerceIn(0.8f, 1.4f)
        preferences.edit().putFloat("fontScale", fontScale).apply()
    }
}

@Composable
fun rememberReaderVisualSettings(): ReaderVisualSettings {
    val context = LocalContext.current.applicationContext
    val settings=remember(context) { ReaderVisualSettings(context) }
    settings.nightMode=com.localreader.twinleaf.ui.theme.LocalReaderDark.current
    return settings
}

@Composable
fun ApplyReaderBrightness(value: Float) {
    val activity = LocalContext.current.findActivity() ?: return
    val original = remember(activity) { activity.window.attributes.screenBrightness }
    LaunchedEffect(activity, value) {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
    }
    DisposableEffect(activity) {
        onDispose {
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = original }
        }
    }
}

@Composable
fun ReaderSettingsPanel(
    visible: Boolean,
    progress: Float,
    locationText: String,
    settings: ReaderVisualSettings,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    portraitMode: PortraitPageMode? = null,
    landscapeMode: LandscapePageMode? = null,
    onPortraitModeChanged: (PortraitPageMode) -> Unit = {},
    onLandscapeModeChanged: (LandscapePageMode) -> Unit = {},
) {
    var section by remember { mutableStateOf(SettingsSection.Catalog) }
    var seekValue by remember(progress) { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }
    var fontValue by remember(settings.fontScale) { mutableFloatStateOf(settings.fontScale) }
    val ink = panelText(settings)
    val accent = if (settings.nightMode) Color(0xFFAEC4FA) else Color(0xFF5D78B9)
    val sliderColors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent,
        inactiveTrackColor = accent.copy(alpha = .15f))
    AnimatedVisibility(modifier = modifier, visible = visible,
        enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        Column(Modifier.fillMaxWidth()
            .semantics { contentDescription = "书本设置面板" }
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(if (settings.nightMode) Color(0xFF242B38) else Color(0xFFF1F5FC))
            .padding(horizontal = 24.dp, vertical = 10.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(32.dp).height(3.dp)
                .background(ink.copy(alpha = .15f), RoundedCornerShape(2.dp)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onSeek(0f) }) {
                    Icon(Icons.Rounded.FirstPage, "跳到开头", tint = ink)
                }
                Slider(value = seekValue, onValueChange = { seekValue = it },
                    onValueChangeFinished = { onSeek(seekValue) }, colors = sliderColors,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        .semantics { contentDescription = "阅读进度，$locationText" })
                IconButton(onClick = { onSeek(1f) }) {
                    Icon(Icons.Rounded.LastPage, "跳到末尾", tint = ink)
                }
            }
            when (section) {
                SettingsSection.Catalog -> Unit
                SettingsSection.Brightness -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BrightnessLow, null, Modifier.padding(12.dp), tint = ink)
                    Slider(value = settings.brightness, onValueChange = settings::updateBrightness,
                        valueRange = .15f..1f, colors = sliderColors,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            .semantics { contentDescription = "屏幕亮度" })
                    Icon(Icons.Rounded.BrightnessHigh, null, Modifier.padding(12.dp), tint = ink)
                }
                SettingsSection.Appearance -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.TextDecrease, null, Modifier.padding(12.dp), tint = ink)
                    Slider(value = fontValue, onValueChange = { fontValue = it },
                        onValueChangeFinished = { settings.updateFontScale(fontValue) },
                        valueRange = .8f..1.4f, colors = sliderColors,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            .semantics { contentDescription = "正文字号" })
                    Icon(Icons.Rounded.TextIncrease, null, Modifier.padding(12.dp), tint = ink)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SettingAction(Icons.Rounded.SwapHoriz, "阅读进度", section == SettingsSection.Catalog, settings) {
                    section = SettingsSection.Catalog
                }
                SettingAction(Icons.Rounded.Brightness6, "亮度", section == SettingsSection.Brightness, settings) {
                    section = SettingsSection.Brightness
                }
                com.localreader.twinleaf.ui.theme.ThemeCycleButton(Modifier.size(52.dp))
                SettingAction(Icons.Rounded.FormatSize, "字号", section == SettingsSection.Appearance, settings) {
                    section = SettingsSection.Appearance
                }
                when {
                    portraitMode != null -> LayoutModeAction(
                        icon = if (portraitMode == PortraitPageMode.SCROLL) Icons.AutoMirrored.Rounded.List else Icons.Rounded.SwapHoriz,
                        label = if (portraitMode == PortraitPageMode.SCROLL) "竖屏连续滚动；点击切换为卡片" else "竖屏卡片翻页；点击切换为连续滚动",
                        settings = settings,
                    ) { onPortraitModeChanged(portraitMode.next()) }
                    landscapeMode != null -> {
                        val icon = when (landscapeMode) {
                            LandscapePageMode.AUTO -> Icons.Rounded.Settings
                            LandscapePageMode.SINGLE -> Icons.Rounded.FirstPage
                            LandscapePageMode.DOUBLE -> Icons.Rounded.LastPage
                        }
                        val label = when (landscapeMode) {
                            LandscapePageMode.AUTO -> "横屏自动布局；点击切换为单页"
                            LandscapePageMode.SINGLE -> "横屏单页；点击切换为双页"
                            LandscapePageMode.DOUBLE -> "横屏双页；点击切换为自动布局"
                        }
                        LayoutModeAction(icon, label, settings) {
                            onLandscapeModeChanged(landscapeMode.next())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutModeAction(
    icon: ImageVector,
    label: String,
    settings: ReaderVisualSettings,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (settings.nightMode) Color(0xFFAEC4FA) else Color(0xFF5D78B9),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun SettingAction(icon: ImageVector, label: String, selected: Boolean,
    settings: ReaderVisualSettings, onClick: () -> Unit) {
    val accent = if (settings.nightMode) Color(0xFFAEC4FA) else Color(0xFF5D78B9)
    IconToggleButton(checked = selected, onCheckedChange = { onClick() },
        modifier = Modifier.size(52.dp).background(if (selected) accent.copy(alpha = .13f) else Color.Transparent,
            RoundedCornerShape(16.dp))) {
        Icon(icon, contentDescription = label, tint = if (selected) accent else panelText(settings),
            modifier = Modifier.size(24.dp))
    }
}

private enum class SettingsSection { Catalog, Brightness, Appearance }

private fun panelText(settings: ReaderVisualSettings) =
    if (settings.nightMode) Color(0xFFE0E7F5) else Color(0xFF52617B)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
