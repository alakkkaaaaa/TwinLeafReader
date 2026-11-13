package com.localreader.twinleaf.data

/** Upgrade pre-portrait reading records without discarding their place in a chapter. */
internal fun restoredEpubFraction(book: LibraryBook, chapterCount: Int): Float {
    if(book.epubFraction>=0f) return book.epubFraction.coerceIn(0f,1f)
    if(book.epubSpread<=0 || chapterCount<=0) return 0f
    val end=(book.progress*chapterCount-book.epubChapter).coerceIn(0f,1f)
    if(end<=0f) return 0f
    val estimatedCount=(book.epubSpread+1f)/end
    return (book.epubSpread/(estimatedCount-1f).coerceAtLeast(1f)).coerceIn(0f,1f)
}
