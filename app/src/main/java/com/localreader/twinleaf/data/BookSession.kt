package com.localreader.twinleaf.data

import android.graphics.Bitmap
import android.net.Uri
import java.io.Closeable
import java.io.File

sealed interface BookSession : Closeable {
    val uri: Uri
    val title: String
}

interface PdfDocument : BookSession {
    val pageCount: Int
    suspend fun renderPage(index: Int, targetWidth: Int, targetHeight: Int): Bitmap
    suspend fun pageAspectRatio(index: Int): Float
}

data class EpubSpineItem(
    val file: File,
    val label: String,
    /** Rendition hints are optional so older saved/session construction remains source compatible. */
    val layout: String? = null,
    val orientation: String? = null,
    val spread: String? = null,
    val pageProgressionDirection: String? = null,
    val properties: Set<String> = emptySet(),
)

interface EpubDocument : BookSession {
    val root: File
    val spine: List<EpubSpineItem>
    val coverImage: File?
    val outline:List<OutlineEntry> get()=spine.mapIndexed { i,item->OutlineEntry("toc:fallback:$i",item.label,1,i,anchor="") }
    val layout: String? get() = null
    val orientation: String? get() = null
    val spread: String? get() = null
    val pageProgressionDirection: String? get() = null
}
