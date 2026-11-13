package com.localreader.twinleaf.data

data class LibraryBook(
    val id: String,
    val uri: String,
    val title: String,
    val format: String,
    val coverPath: String?,
    val progress: Float = 0f,
    val pdfSpread: Int = 0,
    val epubChapter: Int = 0,
    val epubSpread: Int = 0,
    val pdfPage: Int = -1,
    val pdfOffset: Float = 0f,
    val epubFraction: Float = -1f,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    /** Stable [LibraryCategory.id], never the display name. */
    val category:String = UNCATEGORIZED_CATEGORY_ID,
    val customCoverPath:String? = null,
    val folderId: String? = null,
    val importedAt: Long = System.currentTimeMillis(),
)

/** Kept for source compatibility with older integrations. New UI reads repository categories. */
val BOOK_CATEGORIES=listOf("教科书","小说","漫画","未分类")
