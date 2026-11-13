package com.localreader.twinleaf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.localreader.twinleaf.data.BookSession
import com.localreader.twinleaf.data.BookSessionFactory
import com.localreader.twinleaf.data.LibraryBook
import com.localreader.twinleaf.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

sealed interface ReaderState {
    data object Shelf : ReaderState
    data object Loading : ReaderState
    data class Ready(val book: BookSession, val record: LibraryBook) : ReaderState
    data class Note(val record: LibraryBook) : ReaderState
    data class Error(val message: String) : ReaderState
}

class ReaderController(context: Context) {
    private val appContext = context.applicationContext
    private val repository = LibraryRepository(appContext)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var requestId = 0

    var library: List<LibraryBook> by mutableStateOf(repository.load())
        private set
    var state: ReaderState by mutableStateOf(ReaderState.Shelf)
        private set

    suspend fun open(record: LibraryBook) {
        if(record.format.equals("NOTE",true)) {
            ++requestId
            (state as? ReaderState.Ready)?.book?.let { cleanup.launch { runCatching { it.close() } } }
            library=repository.updatePosition(record.id) { it }
            state=ReaderState.Note(library.firstOrNull { it.id==record.id } ?: record)
        } else open(Uri.parse(record.uri))
    }

    fun createNote(title:String,folderId:String?) {
        val record=repository.createNote(title,folderId)
        library=repository.load()
        state=ReaderState.Note(record)
    }

    suspend fun open(uri: Uri,category:String?=null,folderId:String?=null) {
        val request = ++requestId
        val oldBook = (state as? ReaderState.Ready)?.book
        state = ReaderState.Loading
        oldBook?.let { cleanup.launch { runCatching { it.close() } } }
        var opened: BookSession? = null
        try {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val book = BookSessionFactory.open(appContext, uri)
            opened = book
            if (request != requestId) { cleanup.launch { book.close() }; return }
            val record = withContext(Dispatchers.IO) {
                val record=repository.addOrRefresh(book)
                if(category!=null) repository.categorize(setOf(record.id),category)
                if(folderId!=null) repository.moveToFolder(setOf(record.id),folderId)
                repository.load().first { it.id==record.id }
            }
            if (request != requestId) { cleanup.launch { book.close() }; return }
            library = repository.load()
            state = ReaderState.Ready(book, record)
        } catch (error: Throwable) {
            opened?.let { cleanup.launch { runCatching { it.close() } } }
            if (error is CancellationException) throw error
            if (request == requestId) state = ReaderState.Error(error.message ?: "无法打开这本书")
        }
    }

    fun savePdfPosition(spread: Int, progress: Float, page: Int) {
        updateCurrent { it.copy(pdfSpread = spread, pdfPage = page,
            pdfOffset = 0f, progress = progress.coerceIn(0f, 1f)) }
    }

    fun savePdfScroll(page: Int, offset: Float, progress: Float) {
        updateCurrent { it.copy(pdfPage = page, pdfOffset = offset, pdfSpread = (page + 1) / 2, progress = progress) }
    }

    fun removeBook(record: LibraryBook) {
        if (state is ReaderState.Shelf) library = repository.remove(record.id)
    }
    fun refreshLibrary() { library=repository.load() }

    fun saveEpubPosition(chapter: Int, spread: Int, progress: Float, fraction: Float) {
        updateCurrent {
            it.copy(
                epubChapter = chapter,
                epubSpread = spread,
                epubFraction = fraction,
                progress = progress.coerceIn(0f, 1f),
            )
        }
    }

    private fun updateCurrent(transform: (LibraryBook) -> LibraryBook) {
        val ready = state as? ReaderState.Ready ?: return
        library = repository.updatePosition(ready.record.id, transform)
        val updated = library.firstOrNull { it.id == ready.record.id } ?: return
        state = ready.copy(record = updated)
    }

    fun closeBook() {
        ++requestId
        val book = (state as? ReaderState.Ready)?.book
        state = ReaderState.Shelf
        book?.let { cleanup.launch { runCatching { it.close() } } }
    }

    fun returnToShelf() {
        closeBook()
    }

    fun dispose() {
        closeBook()
    }
}
