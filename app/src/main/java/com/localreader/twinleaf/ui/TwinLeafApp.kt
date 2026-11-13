package com.localreader.twinleaf.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localreader.twinleaf.data.EpubDocument
import com.localreader.twinleaf.data.PdfDocument
import com.localreader.twinleaf.data.BookSession
import com.localreader.twinleaf.data.LibraryBook
import com.localreader.twinleaf.data.LandscapePageMode
import com.localreader.twinleaf.data.PortraitPageMode
import com.localreader.twinleaf.data.ReadingLayoutPreferences
import com.localreader.twinleaf.data.restoredEpubFraction
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun TwinLeafApp(
    incomingBook: Uri? = null,
    onIncomingBookConsumed: () -> Unit = {},
) {
    PdfExportHost()
    val context = LocalContext.current
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val controller = remember { ReaderController(context) }
    val scope = rememberCoroutineScope()
    var choosingCategory by remember { mutableStateOf(false) }
    var importCategory by rememberSaveable { mutableStateOf("未分类") }
    var shelfFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var importFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { controller.open(it,importCategory,importFolderId) } }
    }
    val openPicker = { importFolderId=shelfFolderId;choosingCategory=true }
    if(choosingCategory) CategoryDialog(onSelect={importCategory=it;choosingCategory=false;picker.launch(arrayOf("application/pdf","application/epub+zip"))},
        onDismiss={choosingCategory=false},title="为导入书籍选择分类")

    DisposableEffect(controller) { onDispose(controller::dispose) }
    LaunchedEffect(incomingBook) {
        incomingBook?.let {
            controller.open(it)
            onIncomingBookConsumed()
        }
    }

    val shelfVisible = controller.state is ReaderState.Shelf || controller.state is ReaderState.Error
    BackHandler(controller.state !is ReaderState.Shelf) { controller.closeBook() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (shelfVisible) Brush.linearGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface))
                else Brush.radialGradient(listOf(Color(0xFF4B3427), Color(0xFF171310)), radius = 1400f),
            )
            .then(if (shelfVisible) Modifier.systemBarsPadding() else Modifier),
    ) {
        when (val state = controller.state) {
            ReaderState.Shelf -> LibraryScreen(
                books = controller.library,
                onOpen = { scope.launch { controller.open(it) } },
                onAdd = { destination -> importFolderId=destination;choosingCategory=true },
                onRemove = controller::removeBook,
                onChanged = controller::refreshLibrary,
                folderId = shelfFolderId,
                onFolderChange = { shelfFolderId=it },
                onCreateNote = controller::createNote,
            )
            ReaderState.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Button(onClick = controller::closeBook) { Text("返回书架") }
            }
            is ReaderState.Error -> ErrorScreen(
                message = state.message,
                onBack = controller::returnToShelf,
                onRetry = openPicker,
            )
            is ReaderState.Note -> InfiniteNoteScreen(state.record,controller::closeBook)
            is ReaderState.Ready -> ReadyBookScreen(state.book, state.record, portrait, controller)
        }
    }
}

@Composable
private fun ReadyBookScreen(
    book: BookSession,
    record: LibraryBook,
    portrait: Boolean,
    controller: ReaderController,
) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { ReadingLayoutPreferences(context) }
    val bookKey = record.id
    var portraitMode by remember(bookKey) { mutableStateOf(preferences.portraitMode(bookKey)) }
    var landscapeMode by remember(bookKey) { mutableStateOf(preferences.landscapeMode(bookKey)) }
    val changePortrait: (PortraitPageMode) -> Unit = { mode ->
        preferences.setPortraitMode(bookKey, mode)
        portraitMode = mode
    }
    val changeLandscape: (LandscapePageMode) -> Unit = { mode ->
        preferences.setLandscapeMode(bookKey, mode)
        landscapeMode = mode
    }
    when (book) {
        is PdfDocument -> {
            val page = record.pdfPage.takeIf { it >= 0 }
                ?: if (record.pdfSpread == 0) 0 else record.pdfSpread * 2 - 1
            if (portrait) when (portraitMode) {
                PortraitPageMode.SCROLL -> PdfScrollScreen(
                    book = book,
                    initialPage = page,
                    initialOffset = record.pdfOffset,
                    onPositionChanged = controller::savePdfScroll,
                    onClose = controller::closeBook,
                    onPortraitModeChanged = changePortrait,
                )
                PortraitPageMode.CARD -> PdfCardScreen(
                    book = book,
                    initialPage = page,
                    onPositionChanged = controller::savePdfScroll,
                    onClose = controller::closeBook,
                    onPortraitModeChanged = changePortrait,
                )
            } else PdfReaderScreen(
                book = book,
                initialSpread = record.pdfSpread,
                initialPage = record.pdfPage,
                onPositionChanged = controller::savePdfPosition,
                onClose = controller::closeBook,
                layoutMode = landscapeMode,
                onLandscapeModeChanged = changeLandscape,
            )
        }
        is EpubDocument -> {
            val fraction = restoredEpubFraction(record, book.spine.size)
            if (portrait) when (portraitMode) {
                PortraitPageMode.SCROLL -> EpubScrollScreen(
                    book = book,
                    initialChapter = record.epubChapter,
                    initialFraction = fraction,
                    onPositionChanged = controller::saveEpubPosition,
                    onClose = controller::closeBook,
                    onPortraitModeChanged = changePortrait,
                )
                PortraitPageMode.CARD -> EpubCardScreen(
                    book = book,
                    initialChapter = record.epubChapter,
                    initialSpread = record.epubSpread,
                    initialFraction = fraction,
                    onPositionChanged = controller::saveEpubPosition,
                    onClose = controller::closeBook,
                    onPortraitModeChanged = changePortrait,
                )
            } else EpubReaderScreen(
                book = book,
                initialChapter = record.epubChapter,
                initialSpread = record.epubSpread,
                initialFraction = fraction,
                onPositionChanged = controller::saveEpubPosition,
                onClose = controller::closeBook,
                layoutMode = landscapeMode,
                onLandscapeModeChanged = changeLandscape,
            )
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("这本书暂时打不开", color = MaterialTheme.colorScheme.onSurface, fontSize = 28.sp)
        Spacer(Modifier.height(10.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("重新选择") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack) { Text("返回书架") }
    }
}
