package com.localreader.twinleaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.localreader.twinleaf.data.LibraryBook
import com.localreader.twinleaf.data.LibraryFolder
import com.localreader.twinleaf.data.moveShelfItem
import com.localreader.twinleaf.data.insertShelfItem
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private class ShelfDrag {
    var key by mutableStateOf<String?>(null)
    var book by mutableStateOf<LibraryBook?>(null)
    var sourceFolder by mutableStateOf<LibraryFolder?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var grab by mutableStateOf(Offset.Zero)
    var width by mutableIntStateOf(0)
    var preview by mutableStateOf<List<String>?>(null)
    var folder by mutableStateOf<String?>(null)
    var edgeTarget by mutableStateOf<String?>(null)
    var afterTarget by mutableStateOf(false)
    var toRoot by mutableStateOf(false)
    var lastTarget: String? = null
    fun clear() { key = null; book = null; sourceFolder = null; preview = null; folder = null; edgeTarget = null; toRoot = false; lastTarget = null }
}

/** One gesture owner keeps the drag alive when lazy items move or scroll off screen. */
@Composable
internal fun ShelfDragGrid(
    books: List<LibraryBook>, folders: List<LibraryFolder>, order: List<String>, contextKey: String,
    inFolder: Boolean, selecting: Boolean, modifier: Modifier = Modifier,
    onOrder: (List<String>) -> Unit, onMove: (LibraryBook, String?) -> Unit,
    bookContent: @Composable (LibraryBook) -> Unit,
    folderContent: @Composable (LibraryFolder) -> Unit,
) {
    val state = rememberLazyGridState()
    val drag = remember(contextKey) { ShelfDrag() }
    val currentBooks by rememberUpdatedState(books)
    val currentFolders by rememberUpdatedState(folders)
    val currentOrder by rememberUpdatedState(order)
    val commitOrder by rememberUpdatedState(onOrder)
    val commitMove by rememberUpdatedState(onMove)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val speed = with(density) { 14.dp.toPx() }
    val rootZone = with(density) { 44.dp.toPx() }
    val keys = drag.preview ?: order
    val bookMap = books.associateBy { it.id }
    val folderMap = folders.associateBy { it.id }

    fun bounds(item: LazyGridItemInfo) = Rect(item.offset.x.toFloat(), item.offset.y.toFloat(),
        (item.offset.x + item.size.width).toFloat(), (item.offset.y + item.size.height).toFloat())
    fun withinViewport(): Boolean {
        val size = state.layoutInfo.viewportSize
        return drag.pointer.x >= 0 && drag.pointer.x < size.width && drag.pointer.y >= 0 && drag.pointer.y < size.height
    }
    fun updateTarget() {
        val source = drag.key ?: return
        drag.folder = null
        drag.edgeTarget = null
        drag.toRoot = inFolder && drag.book != null && withinViewport() && drag.pointer.y < rootZone
        if (!withinViewport() || drag.toRoot) return
        val hit = state.layoutInfo.visibleItemsInfo.firstOrNull { bounds(it).contains(drag.pointer) } ?: return
        val key = hit.key as? String ?: return
        if (key == source) { drag.lastTarget = null; return }
        if (key.startsWith("folder:") && drag.book != null) {
            // The inner zone distinguishes dropping into a folder from passing its edge.
            val rect = bounds(hit)
            val center = Rect(rect.left + rect.width * .12f, rect.top + rect.height * .12f,
                rect.right - rect.width * .12f, rect.bottom - rect.height * .12f)
            if (center.contains(drag.pointer)) drag.folder = key.removePrefix("folder:")
            else {
                // Defer edge insertion until release so the folder does not flee the pointer
                // before the user reaches its central "move inside" zone.
                drag.edgeTarget = key
                drag.afterTarget = drag.pointer.x >= rect.center.x
            }
            return
        }
        if (key !in currentOrder || key == drag.lastTarget) return
        drag.preview = moveShelfItem(drag.preview ?: currentOrder, source, key)
        drag.lastTarget = key
    }
    val updateTargetNow by rememberUpdatedState { updateTarget() }
    LaunchedEffect(drag.key) {
        while (drag.key != null) {
            val height = state.layoutInfo.viewportSize.height
            val p = drag.pointer
            val delta = when {
                !withinViewport() || drag.toRoot -> 0f
                p.y < edge -> -speed * ((edge - p.y) / edge).coerceIn(0f, 1f)
                p.y > height - edge -> speed * ((p.y - height + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (delta != 0f) { state.scrollBy(delta); updateTargetNow() }
            delay(16)
        }
    }
    Box(modifier.clipToBounds().testTag("shelf-drag-grid").pointerInput(contextKey, selecting) {
        if (!selecting) detectDragGesturesAfterLongPress(
            onDragStart = { position ->
                val hit = state.layoutInfo.visibleItemsInfo.firstOrNull { bounds(it).contains(position) }
                val key = hit?.key as? String
                val book = currentBooks.firstOrNull { "book:${it.id}" == key }
                val folder = currentFolders.firstOrNull { "folder:${it.id}" == key }
                if ((book != null || folder != null) && hit != null) {
                    drag.key = key; drag.book = book; drag.sourceFolder = folder; drag.pointer = position
                    drag.grab = position - Offset(hit.offset.x.toFloat(), hit.offset.y.toFloat())
                    drag.width = hit.size.width; drag.preview = currentOrder
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDrag = { change, _ ->
                if (drag.key != null) { change.consume(); drag.pointer = change.position; updateTargetNow() }
            },
            onDragCancel = { drag.clear() },
            onDragEnd = {
                val book = drag.book
                val key = drag.key
                if (key != null && withinViewport()) {
                    when {
                        book != null && drag.toRoot -> commitMove(book, null)
                        book != null && drag.folder != null && currentFolders.any { it.id == drag.folder } -> commitMove(book, drag.folder)
                        drag.edgeTarget != null -> commitOrder(insertShelfItem(drag.preview ?: currentOrder, key, drag.edgeTarget!!, drag.afterTarget))
                        drag.preview != currentOrder -> drag.preview?.let(commitOrder)
                    }
                }
                drag.clear()
            },
        )
    }) {
        LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), state = state, userScrollEnabled = drag.key == null,
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 20.dp), modifier = Modifier.fillMaxSize()) {
            items(keys, key = { it }) { key ->
                Box(Modifier.testTag("shelf-$key").animateItem().alpha(if (drag.key == key) .18f else 1f)) {
                    if (key.startsWith("folder:")) folderMap[key.removePrefix("folder:")]?.let { folder ->
                        Box(Modifier.border(if (drag.folder == folder.id) 3.dp else 0.dp,
                            if (drag.folder == folder.id) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(18.dp))) { folderContent(folder) }
                        if (drag.edgeTarget == key) Box(Modifier.matchParentSize()) {
                            Box(Modifier.align(if (drag.afterTarget) Alignment.CenterEnd else Alignment.CenterStart)
                                .fillMaxHeight().width(4.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                        }
                    } else bookMap[key.removePrefix("book:")]?.let { bookContent(it) }
                }
            }
        }
        if (drag.key != null) {
            Box(Modifier.offset { IntOffset((drag.pointer.x - drag.grab.x).roundToInt(), (drag.pointer.y - drag.grab.y).roundToInt()) }
                .width(with(density) { drag.width.toDp() }).graphicsLayer { scaleX = 1.04f; scaleY = 1.04f; alpha = .93f }
                .clearAndSetSemantics {}) {
                    drag.book?.let { bookContent(it) }
                    drag.sourceFolder?.let { folderContent(it) }
                }
            if (inFolder) Text("松手移回书架", Modifier.align(Alignment.TopCenter).fillMaxWidth().height(44.dp)
                .background(if (drag.toRoot) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp), color = MaterialTheme.colorScheme.onSurface)
            else drag.folder?.let { id ->
                Text("松手移入 ${folders.firstOrNull { it.id == id }?.name.orEmpty()}",
                    Modifier.align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
