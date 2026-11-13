package com.localreader.twinleaf.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.localreader.twinleaf.data.PdfDocument
import com.localreader.twinleaf.data.PageShortcut
import com.localreader.twinleaf.data.PortraitPageMode
import com.localreader.twinleaf.ui.components.BookChrome
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asComposeColorFilter
import android.graphics.RectF
import com.localreader.twinleaf.ink.InkTool
import com.localreader.twinleaf.input.ReaderNavigationKey
import kotlin.math.abs

@Composable
fun PdfScrollScreen(book: PdfDocument, initialPage: Int, initialOffset: Float,
    onPositionChanged: (Int, Float, Float) -> Unit, onClose: () -> Unit,
    onPortraitModeChanged: (PortraitPageMode) -> Unit = {}) {
    val firstPage = remember(book) { initialPage.coerceIn(0,book.pageCount-1) }
    val initialRatio by produceState<Float?>(null, book) { value = book.pageAspectRatio(firstPage) }
    val lastRatio by produceState<Float?>(null, book) { value = book.pageAspectRatio(book.pageCount-1).coerceIn(.1f,10f) }
    var width by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val density=LocalDensity.current
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var navigationVisible by rememberSaveable { mutableStateOf(false) }
    var outlineMode by rememberSaveable { mutableStateOf(false) }
    val settings = rememberReaderVisualSettings()
    val tools=rememberPdfTools(book)
    val ink=tools.ink
    val scope=rememberCoroutineScope()
    var activeList by remember { mutableStateOf<LazyListState?>(null) }
    val latestPosition by rememberUpdatedState(onPositionChanged)
    var currentPage by remember { mutableIntStateOf(firstPage) }
    var progress by remember { mutableFloatStateOf(firstPage.toFloat()/book.pageCount) }
    ApplyReaderBrightness(settings.brightness)
    BackHandler { if(ink.drawing) return@BackHandler
        if(settingsVisible) settingsVisible=false
        else if(tools.open) {tools.open=false;ink.choose(InkTool.BROWSE)}
        else onClose() }
    Box(Modifier.fillMaxSize()) {
        PdfToolDialogs(book,tools)
        BookChrome(book.title,"${currentPage+1}/${book.pageCount}",progress,{if(!ink.drawing) onClose()},{if(!ink.drawing) settingsVisible=!settingsVisible},
            night=settings.nightMode,navigationLabel="${currentPage+1} / ${book.pageCount}",
            onNavigation={if(!ink.drawing) {outlineMode=false;navigationVisible=true}},onShortcuts={if(!ink.drawing) {outlineMode=false;navigationVisible=true}},
            onOutline={if(!ink.drawing) {outlineMode=true;navigationVisible=true}},chromeVisible=!tools.open,
            extraActions={PdfToolActions(tools)},topOverlay={
                PdfInkToolbar(tools,onPrevious={scope.launch { activeList?.scrollToItem((currentPage-1).coerceAtLeast(0)) }},
                    onNext={scope.launch { activeList?.scrollToItem((currentPage+1).coerceAtMost(book.pageCount-1)) }},
                    modifier=Modifier.align(Alignment.TopCenter))
            }) {
            val externalInput=readerExternalInput(
                enabled=!settingsVisible && !navigationVisible && ink.tool==InkTool.BROWSE && !ink.drawing &&
                    tools.zoom==1f && !tools.appearanceOpen && !tools.exportOpen && !tools.calibrationOpen,
                paginated=false,
                onNavigate={ key ->
                    val list=activeList
                    if(list==null) false else {
                        scope.launch {
                            if(key==ReaderNavigationKey.LEFT || key==ReaderNavigationKey.RIGHT)
                                list.animateScrollToItem((currentPage+key.direction).coerceIn(0,book.pageCount-1))
                            else list.animateScrollBy(key.direction * if(key==ReaderNavigationKey.UP || key==ReaderNavigationKey.DOWN)
                                72f*density.density else viewportHeight*.85f)
                        }
                        true
                    }
                },
                onScroll={ x,y,_ ->
                    val list=activeList
                    if(list==null) false else {
                        scope.launch { list.scrollBy(if(abs(x)>abs(y)) x else y) }
                        true
                    }
                })
            Box(Modifier.fillMaxSize().then(externalInput).onSizeChanged { width=it.width; viewportHeight=it.height }) {
                if(width>0 && initialRatio!=null && lastRatio!=null) {
                    val list = rememberLazyListState(firstPage,(width/initialRatio!!*initialOffset).roundToInt())
                    LaunchedEffect(list) { activeList=list }
                    LaunchedEffect(list,book) {
                        snapshotFlow {
                            val info=list.layoutInfo.visibleItemsInfo.firstOrNull { it.index==list.firstVisibleItemIndex }
                            if(info==null) null else Triple(list.firstVisibleItemIndex,
                                (list.firstVisibleItemScrollOffset.toFloat()/info.size.coerceAtLeast(1)).coerceIn(0f,1f),
                                !list.canScrollForward)
                        }.distinctUntilChanged().collect { location ->
                            if(location!=null) {
                                currentPage=location.first
                                progress=if(location.third) 1f else (location.first+location.second)/book.pageCount
                                latestPosition(location.first,location.second,progress)
                            }
                        }
                    }
                    // Let even a short final page align with the top when jumped to explicitly.
                    val trailingSpace=with(density) { (viewportHeight-width/lastRatio!!).coerceAtLeast(0f).toDp() }
                    LazyColumn(state=list,userScrollEnabled=ink.tool==InkTool.BROWSE && !ink.drawing && tools.zoom==1f,
                        contentPadding=PaddingValues(bottom=trailingSpace),modifier=Modifier.fillMaxSize()
                        .semantics { contentDescription="PDF 竖屏连续阅读" }) {
                        items((0 until book.pageCount).toList(), key={it}) { index ->
                            PdfScrollPage(book,index,width,settings.nightMode,tools)
                        }
                    }
                    ReaderSettingsPanel(settingsVisible,progress,"${currentPage+1}/${book.pageCount}",settings,
                        onSeek={value->scope.launch { list.scrollToItem((value*(book.pageCount-1)).roundToInt()) }},
                        modifier=Modifier.align(Alignment.BottomCenter),
                        portraitMode=PortraitPageMode.SCROLL,
                        onPortraitModeChanged=onPortraitModeChanged)
                    if(navigationVisible) PageNavigationDialog(book.uri.toString(),currentPage+1,book.pageCount,
                        initialOutline=outlineMode,
                        night=settings.nightMode,targetForPage={PageShortcut(it)},onJump={target->
                            scope.launch { list.scrollToItem((target.page-1).coerceIn(0,book.pageCount-1)) }
                            settingsVisible=false
                        },onDismiss={navigationVisible=false})
                } else CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun PdfScrollPage(book: PdfDocument, page: Int, width: Int, night: Boolean,tools:PdfToolsState) {
    val ratio by produceState(.7071f,book,page) { value=book.pageAspectRatio(page).coerceIn(.1f,10f) }
    var error by remember { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null,book,page,width,ratio,night) {
        error=null
        try {
            val renderWidth=minOf(width,1800,kotlin.math.sqrt(6_000_000f*ratio).toInt()).coerceAtLeast(1)
            value=book.renderPage(page,renderWidth,(renderWidth/ratio).roundToInt().coerceIn(1,10000))
        } catch(e:kotlinx.coroutines.CancellationException) { throw e }
        catch(e:Exception) { error=e.message }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(ratio)
        .background(if(night) Color(0xFF24231F) else Color(0xFFFAF7EF))) {
        PdfZoomBox(tools,page,if(tools.ink.visible) mapOf(page to RectF(0f,0f,width.toFloat(),width/ratio)) else null) {
            bitmap?.let { Image(it.asImageBitmap(),"第 ${page+1} 页",Modifier.fillMaxSize(),
                colorFilter=tools.appearance.effective.filter(night)?.asComposeColorFilter()) }
        }
        if(bitmap==null && error==null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        error?.let { Text(it,Modifier.align(Alignment.Center),color=Color.Red) }
    }
}
