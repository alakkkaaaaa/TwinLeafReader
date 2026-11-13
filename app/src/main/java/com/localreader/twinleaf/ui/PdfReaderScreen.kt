package com.localreader.twinleaf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.localreader.twinleaf.data.*
import com.localreader.twinleaf.ink.*
import com.localreader.twinleaf.pdf.PdfAppearance
import com.localreader.twinleaf.ui.components.BookChrome
import com.localreader.twinleaf.ui.components.CardPageSurface
import com.localreader.twinleaf.ui.components.PageTurnSurface
import com.localreader.twinleaf.ui.components.PageTurnTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun PdfReaderScreen(
    book: PdfDocument,
    initialSpread: Int,
    initialPage: Int = -1,
    onPositionChanged: (Int, Float, Int) -> Unit,
    onClose: () -> Unit,
    layoutMode: LandscapePageMode = LandscapePageMode.DOUBLE,
    portraitMode: PortraitPageMode? = null,
    onPortraitModeChanged: (PortraitPageMode) -> Unit = {},
    onLandscapeModeChanged: (LandscapePageMode) -> Unit = {},
) {
    val aspectRatios by produceState(List(book.pageCount) { .7071f }, book) {
        value = (0 until book.pageCount).map { book.pageAspectRatio(it).coerceIn(.1f, 10f) }
    }
    val groupingMode = when (layoutMode) {
        LandscapePageMode.AUTO -> PdfGroupingMode.ADAPTIVE
        LandscapePageMode.SINGLE -> PdfGroupingMode.SINGLE
        LandscapePageMode.DOUBLE -> PdfGroupingMode.DOUBLE
    }
    val groups = remember(book.pageCount, aspectRatios, groupingMode) {
        SpreadLayout.groups(book.pageCount, aspectRatios, groupingMode)
    }
    val initialPhysicalPage = remember(book.uri, initialPage, initialSpread) {
        (initialPage.takeIf { it >= 0 } ?: if (initialSpread == 0) 0 else initialSpread * 2 - 1)
            .coerceIn(0, (book.pageCount - 1).coerceAtLeast(0))
    }
    var groupIndex by rememberSaveable(book.uri.toString(), layoutMode.name) {
        mutableIntStateOf(SpreadLayout.groupContaining(groups, initialPhysicalPage))
    }
    var focusedPage by rememberSaveable(book.uri.toString()) { mutableIntStateOf(initialPhysicalPage) }
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var navigationVisible by rememberSaveable { mutableStateOf(false) }
    var outlineMode by rememberSaveable { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val settings = rememberReaderVisualSettings()
    val tools = rememberPdfTools(book)
    val ink = tools.ink
    val editing = ink.visible && ink.tool != InkTool.BROWSE
    val appearance = tools.appearance.effective
    val inkRevision = if (editing || !ink.visible) -1L else ink.revision
    var frames by remember(book, size, settings.nightMode, appearance, editing, ink.visible, inkRevision, ink.ready, groups) {
        mutableStateOf<Map<Int, Bitmap>>(emptyMap())
    }
    var pageRects by remember(book, size, groupIndex, groups) { mutableStateOf<Map<Int, RectF>>(emptyMap()) }
    var turnDirection by remember { mutableIntStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }
    var turnTarget by remember { mutableStateOf<PageTurnTarget?>(null) }
    val progress = if (book.pageCount <= 1) 1f else focusedPage.toFloat() / (book.pageCount - 1)

    ApplyReaderBrightness(settings.brightness)
    LaunchedEffect(groups) {
        groupIndex = SpreadLayout.groupContaining(groups, focusedPage)
            .coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    }
    BackHandler {
        if (ink.drawing) return@BackHandler
        if (settingsVisible) settingsVisible = false
        else if (tools.open) { tools.open = false; ink.choose(InkTool.BROWSE) }
        else onClose()
    }
    LaunchedEffect(book, size, groupIndex, groups, settings.nightMode, appearance, editing, ink.visible, inkRevision, ink.ready) {
        if (size.width < 2 || size.height < 1 || groups.isEmpty()) return@LaunchedEffect
        try {
            error = null
            pageRects = pageRectsForGroup(book, groups[groupIndex], size.width, size.height)
            val wanted = listOf(groupIndex, groupIndex + turnDirection, groupIndex + turnDirection * 2, groupIndex - turnDirection)
                .filter { it in groups.indices }
            val prepared = frames.filterKeys { it in wanted }.toMutableMap()
            frames = prepared.toMap()
            for (index in wanted) {
                if (index in prepared) continue
                prepared[index] = renderPdfGroup(book, groups[index], size.width, size.height, settings.nightMode, appearance,
                    if (ink.visible && !editing) ink.pages else emptyMap())
                frames = prepared.toMap()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "页面渲染失败" }
    }
    LaunchedEffect(groupIndex, focusedPage) { onPositionChanged(groupIndex, progress, focusedPage) }

    fun turn(direction: Int) {
        if (groups.isEmpty()) return
        tools.zoom = 1f
        turnDirection = direction
        groupIndex = (groupIndex + direction).coerceIn(groups.indices)
        focusedPage = groups[groupIndex].focusedPage
    }

    Box(Modifier.fillMaxSize()) {
        PdfToolDialogs(book, tools)
        val group = groups.getOrNull(groupIndex)
        BookChrome(book.title, "${focusedPage + 1}/${book.pageCount}", progress,
            { if (!ink.drawing) onClose() }, { if (!ink.drawing) settingsVisible = !settingsVisible },
            night = settings.nightMode, navigationLabel = "${focusedPage + 1} / ${book.pageCount}",
            onNavigation = { if (!ink.drawing) { outlineMode = false; navigationVisible = true } },
            onShortcuts = { if (!ink.drawing) { outlineMode = false; navigationVisible = true } },
            onOutline = { if (!ink.drawing) { outlineMode = true; navigationVisible = true } },
            chromeVisible = !tools.open, extraActions = { PdfToolActions(tools) },
            topOverlay = { PdfInkToolbar(tools, { turn(-1) }, { turn(1) }, Modifier.align(Alignment.TopCenter)) }) {
            val externalInput = readerExternalInput(
                enabled = !settingsVisible && !navigationVisible && !editing && !ink.drawing && tools.zoom == 1f &&
                    !tools.appearanceOpen && !tools.exportOpen && !tools.calibrationOpen,
                paginated = true, onTurn = { turnTarget?.turnPage(it) ?: false })
            Box(Modifier.fillMaxSize().then(externalInput).onSizeChanged { size = it }) {
                if (group != null) {
                    val previousGroup = groups.getOrNull(groupIndex - 1)
                    val nextGroup = groups.getOrNull(groupIndex + 1)
                    val useCurl = group.isDouble &&
                        (previousGroup == null || previousGroup.isDouble) &&
                        (nextGroup == null || nextGroup.isDouble)
                    PdfZoomBox(tools, groupIndex, if (editing) pageRects else null) {
                        if (useCurl) PageTurnSurface(frames[groupIndex], frames[groupIndex - 1], frames[groupIndex + 1], settings.nightMode,
                            ::turn, { settingsVisible = !settingsVisible }, !editing && tools.zoom == 1f,
                            onView = { turnTarget = it })
                        else CardPageSurface(frames[groupIndex], frames[groupIndex - 1], frames[groupIndex + 1], settings.nightMode,
                            ::turn, { settingsVisible = !settingsVisible }, !editing && tools.zoom == 1f,
                            onView = { turnTarget = it })
                    }
                }
                if (frames[groupIndex] == null && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                error?.let { Text(it, color = Color.Red, modifier = Modifier.align(Alignment.Center)) }
            }
        }
        ReaderSettingsPanel(settingsVisible, progress, "${focusedPage + 1}/${book.pageCount}", settings,
            onSeek = { value ->
                focusedPage = ((book.pageCount - 1) * value).roundToInt()
                groupIndex = SpreadLayout.groupContaining(groups, focusedPage)
            }, modifier = Modifier.align(Alignment.BottomCenter),
            portraitMode = portraitMode,
            landscapeMode = layoutMode.takeIf { portraitMode == null },
            onPortraitModeChanged = onPortraitModeChanged,
            onLandscapeModeChanged = onLandscapeModeChanged)
        if (navigationVisible) PageNavigationDialog(book.uri.toString(), focusedPage + 1, book.pageCount,
            initialOutline = outlineMode, night = settings.nightMode, targetForPage = { PageShortcut(it) },
            onJump = { target ->
                focusedPage = (target.page - 1).coerceIn(0, book.pageCount - 1)
                groupIndex = SpreadLayout.groupContaining(groups, focusedPage)
                settingsVisible = false
            }, onDismiss = { navigationVisible = false })
    }
}

@Composable
fun PdfCardScreen(book: PdfDocument, initialPage: Int,
    onPositionChanged: (Int, Float, Float) -> Unit, onClose: () -> Unit,
    onPortraitModeChanged: (PortraitPageMode) -> Unit = {}) {
    PdfReaderScreen(book, initialPage, initialPage,
        { _, progress, page -> onPositionChanged(page, 0f, progress) }, onClose,
        layoutMode = LandscapePageMode.SINGLE,
        portraitMode = PortraitPageMode.CARD,
        onPortraitModeChanged = onPortraitModeChanged)
}

private suspend fun pageRectsForGroup(book: PdfDocument, group: PdfPageGroup, width: Int, height: Int): Map<Int, RectF> {
    if (group.pages.isEmpty()) return emptyMap()
    return if (group.isDouble) {
        val half = width / 2f
        buildMap {
            group.left?.let { put(it, fitPage(0f, 0f, half, height.toFloat(), book.pageAspectRatio(it))) }
            group.right?.let { put(it, fitPage(half, 0f, half, height.toFloat(), book.pageAspectRatio(it))) }
        }
    } else {
        val page = group.pages.single()
        mapOf(page to fitPage(0f, 0f, width.toFloat(), height.toFloat(), book.pageAspectRatio(page)))
    }
}

internal suspend fun renderPdfGroup(book: PdfDocument, group: PdfPageGroup, width: Int, height: Int, night: Boolean,
    appearance: PdfAppearance = PdfAppearance(), ink: Map<Int, List<InkStroke>> = emptyMap()): Bitmap =
    withContext(Dispatchers.IO) {
        val result = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        canvas.drawColor(if (night && !appearance.original) AColor.rgb(25, 30, 40) else AColor.rgb(250, 247, 239))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = appearance.filter(night) }
        if (group.isDouble) {
            val half = width / 2
            suspend fun draw(page: Int, left: Float) {
                canvas.drawBitmap(book.renderPage(page, half, height), left, 0f, paint)
                drawInk(canvas, fitPage(left, 0f, half.toFloat(), height.toFloat(), book.pageAspectRatio(page)), ink[page].orEmpty())
            }
            group.left?.let { draw(it, 0f) }; group.right?.let { draw(it, half.toFloat()) }
        } else group.pages.singleOrNull()?.let { page ->
            canvas.drawBitmap(book.renderPage(page, width, height), 0f, 0f, paint)
            drawInk(canvas, fitPage(0f, 0f, width.toFloat(), height.toFloat(), book.pageAspectRatio(page)), ink[page].orEmpty())
        }
        result.prepareToDraw(); result
    }

internal suspend fun renderPdfSpread(book: PdfDocument, spread: Int, width: Int, height: Int, night: Boolean,
    appearance: PdfAppearance = PdfAppearance(), ink: Map<Int, List<InkStroke>> = emptyMap()): Bitmap {
    val pair = SpreadLayout.pagesFor(spread, book.pageCount)
    return renderPdfGroup(book, PdfPageGroup(pair.left, pair.right), width, height, night, appearance, ink)
}
