package com.localreader.twinleaf.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale

@Composable
fun ReaderImmersiveBars() {
    val context = LocalContext.current
    DisposableEffect(context) {
        var current: Context = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        val activity = current as? Activity
        val bars = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
fun BookChrome(title: String, location: String, progress: Float, onClose: () -> Unit,
    onSpineClick: () -> Unit, night: Boolean = false, navigationLabel: String? = null,
    onNavigation: (() -> Unit)? = null, onShortcuts: (() -> Unit)? = null,
    onOutline: (() -> Unit)? = null,
    chromeVisible: Boolean = true,
    extraActions: @Composable () -> Unit = {},
    topOverlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit) {
    ReaderImmersiveBars()
    val background = if(night) Color(0xFF242B38) else Color(0xFFFAF7EF)
    val chrome=readerChromePalette(night)
    val shape=RoundedCornerShape(26.dp)
    Box(Modifier.fillMaxSize().background(background)) {
        // Keep the viewport identical when the reading chrome is replaced by ink tools.
        // Only page content is clipped; the independent overlay below may grow beyond 64 dp.
        Box(Modifier.fillMaxSize().padding(top = 64.dp).clipToBounds()) { content() }
        if(chromeVisible) Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp).height(52.dp)
            .align(Alignment.TopCenter).zIndex(2f).shadow(8.dp,shape,clip=false).clip(shape)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .background(Brush.verticalGradient(listOf(chrome.gradientTop,chrome.gradientBottom)))
            .border(1.dp,chrome.border,shape), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.width(60.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回书架", Modifier.size(24.dp), tint = chrome.content)
            }
            Text(title, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = chrome.muted, fontSize = 12.sp)
            if(onNavigation!=null) {
                TextButton(onClick=onNavigation,modifier=Modifier.semantics { contentDescription="页码跳转" }) {
                    Text(navigationLabel ?: location,color=chrome.content,fontSize=12.sp)
                }
            } else Text(String.format(Locale.ROOT, "%.1f%%", progress * 100),color=chrome.content,fontSize=11.sp)
            onOutline?.let { action -> IconButton(onClick=action) {
                Icon(Icons.AutoMirrored.Rounded.List,"章节大纲",Modifier.size(24.dp),tint=chrome.content)
            } }
            onShortcuts?.let { action -> IconButton(onClick=action) {
                Icon(Icons.Rounded.Bookmarks,"快捷页",Modifier.size(24.dp),tint=chrome.content)
            } }
            extraActions()
            IconButton(onClick = onSpineClick, modifier = Modifier.width(56.dp)) {
                Icon(Icons.Rounded.Tune, "书本设置", Modifier.size(24.dp), tint = chrome.content)
            }
        }
        if(!chromeVisible) {
            // Do not add clip(), a fixed toolbar height, or an offscreen layer here:
            // selected instruments extend above their tray, including during animation.
            Box(Modifier.fillMaxSize().zIndex(3f), content=topOverlay)
        }
    }
}
