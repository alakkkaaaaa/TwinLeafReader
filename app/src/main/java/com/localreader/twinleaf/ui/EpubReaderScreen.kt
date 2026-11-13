package com.localreader.twinleaf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import com.localreader.twinleaf.data.PageShortcut
import androidx.compose.ui.viewinterop.AndroidView
import com.localreader.twinleaf.data.EpubDocument
import com.localreader.twinleaf.data.LandscapePageMode
import com.localreader.twinleaf.data.PortraitPageMode
import com.localreader.twinleaf.epub.EpubLocation
import com.localreader.twinleaf.epub.EpubRenderView
import com.localreader.twinleaf.ui.components.BookChrome
import kotlin.math.floor

@Composable
fun EpubReaderScreen(
    book: EpubDocument,
    initialChapter: Int,
    initialSpread: Int,
    initialFraction: Float = -1f,
    onPositionChanged: (Int, Int, Float, Float) -> Unit,
    onClose: () -> Unit,
    layoutMode: LandscapePageMode = LandscapePageMode.AUTO,
    layoutNamespace: String = "L",
    portraitMode: PortraitPageMode? = null,
    onPortraitModeChanged: (PortraitPageMode) -> Unit = {},
    onLandscapeModeChanged: (LandscapePageMode) -> Unit = {},
) {
    var restoreFraction by remember(book) { mutableStateOf(initialFraction.takeIf { it >= 0f }) }
    var chapter by rememberSaveable(book.uri.toString()) { mutableIntStateOf(initialChapter.coerceIn(book.spine.indices)) }
    var spread by rememberSaveable(book.uri.toString()) { mutableIntStateOf(initialSpread.coerceAtLeast(0)) }
    var count by remember { mutableIntStateOf((initialSpread + 1).coerceAtLeast(1)) }
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var navigationVisible by rememberSaveable { mutableStateOf(false) }
    var outlineMode by rememberSaveable { mutableStateOf(false) }
    var pageCount by remember { mutableIntStateOf(1) }
    var effectiveColumns by remember { mutableIntStateOf(if (layoutMode == LandscapePageMode.SINGLE) 1 else 2) }
    var focusedPage by remember { mutableIntStateOf(1) }
    var pendingPage by remember { mutableStateOf<Int?>(null) }
    var pendingAnchor by remember { mutableStateOf<String?>(null) }
    var currentOutlineId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var renderView by remember { mutableStateOf<EpubRenderView?>(null) }
    val settings = rememberReaderVisualSettings()
    val configuration=LocalConfiguration.current
    val layout="$layoutNamespace:${layoutMode.name}:${configuration.screenWidthDp}:${configuration.screenHeightDp}:${settings.fontScale}"
    val progress = (chapter + (spread + 1f) / count) / book.spine.size
    val latestPosition by rememberUpdatedState(onPositionChanged)
    ApplyReaderBrightness(settings.brightness)
    BackHandler { if (settingsVisible) settingsVisible = false else onClose() }
    Box(Modifier.fillMaxSize()) {
        BookChrome(book.title, "章节 ${chapter + 1}/${book.spine.size} · ${spread + 1}/$count", progress,
            onClose, { settingsVisible = !settingsVisible },night=settings.nightMode,
            navigationLabel="${chapter+1} · $focusedPage / $pageCount",onNavigation={outlineMode=false;navigationVisible=true},
            onOutline={outlineMode=true;navigationVisible=true},
            onShortcuts={outlineMode=false;navigationVisible=true}) {
            AndroidView(
                modifier = Modifier.fillMaxSize().then(readerExternalInput(
                    enabled = !settingsVisible && !navigationVisible,
                    paginated = true, onTurn = { renderView?.turnPage(it) ?: false })),
                factory = { EpubRenderView(it).also { view -> renderView = view } },
                update = { view ->
                    view.onPageCount = { pageCount=it }
                    view.onOutlinePosition={currentOutlineId=it}
                    view.onPosition = { location, pages ->
                        focusedPage=(pendingPage ?: (location.spread*effectiveColumns+1)).coerceIn(1,pageCount)
                        pendingPage=null
                        pendingAnchor=null
                        restoreFraction = null
                        chapter = location.chapter; spread = location.spread; count = pages
                        latestPosition(chapter, spread, (chapter + (spread + 1f) / pages) / book.spine.size,
                            if(pages<=1) 0f else spread.toFloat()/(pages-1))
                    }
                    view.onError = { error = it }
                    view.onColumnsChanged = { effectiveColumns = it }
                    view.onSpine = { settingsVisible = !settingsVisible }
                    view.configure(
                        book,
                        EpubLocation(chapter, spread, restoreFraction,pendingPage,pendingAnchor),
                        settings.fontScale,
                        settings.nightMode,
                        columns = if (layoutMode == LandscapePageMode.SINGLE) 1 else 2,
                        cardMode = layoutMode == LandscapePageMode.SINGLE,
                    )
                },
                onRelease = { it.release(); if(renderView === it) renderView = null },
            )
            error?.let { Text(it, color = Color.Red, modifier = Modifier.align(Alignment.Center)) }
        }
        ReaderSettingsPanel(settingsVisible, progress, "章节 ${chapter + 1}", settings,
            onSeek = { chapter = floor(it.coerceIn(0f, 0.9999f) * book.spine.size).toInt(); spread = 0 },
            modifier = Modifier.align(Alignment.BottomCenter),
            portraitMode = portraitMode,
            landscapeMode = layoutMode.takeIf { portraitMode == null },
            onPortraitModeChanged = onPortraitModeChanged,
            onLandscapeModeChanged = onLandscapeModeChanged)
        if(navigationVisible) PageNavigationDialog(book.uri.toString(),focusedPage,pageCount,chapter,settings.nightMode,
            initialOutline=outlineMode,chapterCount=book.spine.size,
            builtinOutline=book.outline,
            currentBuiltinId=currentOutlineId,
            targetForPage={page->PageShortcut(page,chapter,if(count<=1) 0f else ((page-1)/effectiveColumns).toFloat()/(count-1),layout)},
            onJump={target->
                chapter=target.chapter.coerceIn(book.spine.indices)
                pendingAnchor=target.anchor
                spread=0
                if(target.layout==layout) { pendingPage=target.page; restoreFraction=null }
                else { pendingPage=null; restoreFraction=target.fraction }
                settingsVisible=false
            },onDismiss={navigationVisible=false})
    }
}

/** Portrait/single-page entry point used by the app router when CARD navigation is selected. */
@Composable
fun EpubCardScreen(
    book: EpubDocument,
    initialChapter: Int,
    initialSpread: Int,
    initialFraction: Float = -1f,
    onPositionChanged: (Int, Int, Float, Float) -> Unit,
    onClose: () -> Unit,
    onPortraitModeChanged: (PortraitPageMode) -> Unit = {},
) = EpubReaderScreen(
    book = book,
    initialChapter = initialChapter,
    initialSpread = initialSpread,
    initialFraction = initialFraction,
    onPositionChanged = onPositionChanged,
    onClose = onClose,
    layoutMode = LandscapePageMode.SINGLE,
    layoutNamespace = "PC",
    portraitMode = PortraitPageMode.CARD,
    onPortraitModeChanged = onPortraitModeChanged,
)
