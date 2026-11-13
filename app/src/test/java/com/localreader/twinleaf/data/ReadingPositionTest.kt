package com.localreader.twinleaf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPositionTest {
    private val book=LibraryBook("id","file:///sample.epub","Sample","EPUB",null)
    @Test fun explicitFractionWins() {
        assertEquals(.42f,restoredEpubFraction(book.copy(epubFraction=.42f),10),.0001f)
    }
    @Test fun oldMiddlePositionIsRecovered() {
        // Chapter 2 of 10, spread 4 of 10: previous progress was (2 + 5/10) / 10.
        assertEquals(4f/9,restoredEpubFraction(book.copy(epubChapter=2,epubSpread=4,progress=.25f),10),.0001f)
    }
    @Test fun oldEndPositionIsRecovered() {
        assertEquals(1f,restoredEpubFraction(book.copy(epubChapter=2,epubSpread=9,progress=.3f),10),.0001f)
    }
    @Test fun firstSpreadStaysAtStart() {
        assertEquals(0f,restoredEpubFraction(book.copy(progress=.1f),10),0f)
    }
}
