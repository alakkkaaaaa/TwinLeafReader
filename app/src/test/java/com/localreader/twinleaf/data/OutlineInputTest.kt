package com.localreader.twinleaf.data

import org.junit.Assert.*
import org.junit.Test

class OutlineInputTest {
    @Test fun pdfUsesOneBasedPagesAndTrimmedName() {
        val entry=outlineEntry("id"," 第一章 ","12","",0,50)!!
        assertEquals("第一章",entry.title);assertEquals(12,entry.page);assertEquals(-1,entry.chapter)
    }
    @Test fun epubUsesChapterAndLocalPage() {
        val entry=outlineEntry("id","Chapter two","1","2",3,10)!!
        assertEquals(1,entry.chapter);assertEquals("2 · 1",entry.locationLabel)
    }
    @Test fun invalidNamesAndPagesAreRejected() {
        for(name in listOf("","   ","x".repeat(101))) assertNull(outlineEntry("id",name,"1","",0,8))
        for(page in listOf("","0","-1","9","999999999999")) assertNull(outlineEntry("id","A",page,"",0,8))
    }
    @Test fun invalidChaptersAreRejected() {
        for(chapter in listOf("","0","-1","4")) assertNull(outlineEntry("id","A","1",chapter,3,8))
    }
}
