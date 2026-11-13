package com.localreader.twinleaf.epub

import android.content.Context
import android.net.Uri
import com.localreader.twinleaf.data.EpubDocument
import com.localreader.twinleaf.data.EpubSpineItem
import java.io.File

class AndroidEpubDocument private constructor(
    override val uri: Uri,
    override val title: String,
    override val spine: List<EpubSpineItem>,
    override val coverImage: File?,
    override val root: File,
    override val outline:List<com.localreader.twinleaf.data.OutlineEntry>,
    override val layout: String?,
    override val orientation: String?,
    override val spread: String?,
    override val pageProgressionDirection: String?,
) : EpubDocument {
    override fun close() {
        root.deleteRecursively()
    }

    companion object {
        fun open(context: Context, uri: Uri, fallbackTitle: String): AndroidEpubDocument {
            val extracted = EpubParser.extractAndParse(context, uri, fallbackTitle)
            return AndroidEpubDocument(
                uri = uri,
                title = extracted.title,
                spine = extracted.spine,
                coverImage = extracted.coverImage,
                root = extracted.root,
                outline = extracted.outline,
                layout = extracted.layout,
                orientation = extracted.orientation,
                spread = extracted.spread,
                pageProgressionDirection = extracted.pageProgressionDirection,
            )
        }
    }
}
