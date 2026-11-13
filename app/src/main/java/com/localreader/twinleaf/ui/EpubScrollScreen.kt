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
import com.localreader.twinleaf.data.PortraitPageMode
import com.localreader.twinleaf.epub.EpubScrollView
import com.localreader.twinleaf.ui.components.BookChrome
import kotlin.math.floor

@Composable
fun EpubScrollScreen(book:EpubDocument,initialChapter:Int,initialFraction:Float,
    onPositionChanged:(Int,Int,Float,Float)->Unit,onClose:()->Unit,
    onPortraitModeChanged:(PortraitPageMode)->Unit={}) {
    var chapter by remember(book) { mutableIntStateOf(initialChapter.coerceIn(book.spine.indices)) }
    var fraction by remember(book) { mutableFloatStateOf(initialFraction.coerceIn(0f,1f)) }
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var navigationVisible by rememberSaveable { mutableStateOf(false) }
    var outlineMode by rememberSaveable { mutableStateOf(false) }
    var pageCount by remember { mutableIntStateOf(1) }
    var currentPage by remember { mutableIntStateOf(1) }
    var pendingPage by remember { mutableStateOf<Int?>(null) }
    var pendingAnchor by remember { mutableStateOf<String?>(null) }
    var currentOutlineId by remember { mutableStateOf<String?>(null) }
    var scrollView by remember { mutableStateOf<EpubScrollView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val settings=rememberReaderVisualSettings()
    val configuration=LocalConfiguration.current
    val layout="P:${configuration.screenWidthDp}:${configuration.screenHeightDp}:${settings.fontScale}"
    val latestPosition by rememberUpdatedState(onPositionChanged)
    val progress=(chapter+fraction)/book.spine.size
    ApplyReaderBrightness(settings.brightness)
    BackHandler { if(settingsVisible) settingsVisible=false else onClose() }
    Box(Modifier.fillMaxSize()) {
        BookChrome(book.title,"${chapter+1}/${book.spine.size}",progress,onClose,{settingsVisible=!settingsVisible},
            night=settings.nightMode,navigationLabel="${chapter+1} · $currentPage / $pageCount",
            onNavigation={outlineMode=false;navigationVisible=true},onShortcuts={outlineMode=false;navigationVisible=true},
            onOutline={outlineMode=true;navigationVisible=true}) {
            AndroidView(modifier=Modifier.fillMaxSize().then(readerExternalInput(
                enabled=!settingsVisible && !navigationVisible,paginated=false,
                onNavigate={scrollView?.navigate(it) ?: false},
                onScroll={x,y,time->scrollView?.scrollFromExternal(x,y,time) ?: false})),
                factory={EpubScrollView(it).also { v->scrollView=v }},update={view->
                view.onPageMetrics={page,total->currentPage=page;pageCount=total}
                view.onOutlinePosition={currentOutlineId=it}
                view.onPosition={index,offset->
                    pendingPage=null
                    pendingAnchor=null
                    chapter=index; fraction=offset
                    latestPosition(index,0,(index+offset)/book.spine.size,offset)
                }
                view.onError={error=it}
                view.configure(book,chapter,fraction,settings.fontScale,settings.nightMode,pendingPage,pendingAnchor)
            },onRelease={it.release();scrollView=null})
            error?.let { Text(it,color=Color.Red,modifier=Modifier.align(Alignment.Center)) }
        }
        ReaderSettingsPanel(settingsVisible,progress,"${chapter+1}/${book.spine.size}",settings,
            onSeek={value->
                val location=value.coerceIn(0f,.999999f)*book.spine.size
                chapter=floor(location).toInt(); fraction=if(value>=1f) 1f else location-chapter
            },modifier=Modifier.align(Alignment.BottomCenter),
            portraitMode=PortraitPageMode.SCROLL,
            onPortraitModeChanged=onPortraitModeChanged)
        if(navigationVisible) PageNavigationDialog(book.uri.toString(),currentPage,pageCount,chapter,settings.nightMode,
            initialOutline=outlineMode,chapterCount=book.spine.size,
            builtinOutline=book.outline,
            currentBuiltinId=currentOutlineId,
            targetForPage={page->PageShortcut(page,chapter,scrollView?.fractionForPage(page) ?: fraction,layout)},
            onJump={target->
                chapter=target.chapter.coerceIn(book.spine.indices)
                pendingAnchor=target.anchor
                fraction=target.fraction
                pendingPage=target.page.takeIf { target.layout==layout }
                settingsVisible=false
            },onDismiss={navigationVisible=false})
    }
}
