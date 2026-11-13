package com.localreader.twinleaf.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.localreader.twinleaf.epub.AndroidEpubDocument
import com.localreader.twinleaf.pdf.AndroidPdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object BookSessionFactory {
    suspend fun open(context: Context, uri: Uri): BookSession = withContext(Dispatchers.IO) {
        val title = displayName(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        when {
            mime == "application/pdf" || title.endsWith(".pdf", true) ->
                AndroidPdfDocument.open(context, uri, title)
            mime == "application/epub+zip" || title.endsWith(".epub", true) ->
                AndroidEpubDocument.open(context, uri, title)
            else -> error("仅支持 PDF 和 EPUB 文件")
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        if(uri.scheme.equals("file",true)) return uri.path?.let(::File)?.name ?: "未命名书籍"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) return cursor.getString(column)
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "未命名书籍"
    }
}
