package com.localreader.twinleaf.data

import org.junit.Assert.*
import org.junit.Test

class OutlineTreeTest {
    @Test fun txtAcceptsBomCommentsAndLevels() {
        val entries=OutlineImport.parse("\uFEFF# 目录\n1\t9\t第一章\n2\t13\t第一节\n1\t21\t第二章",30)
        assertEquals(listOf(1,2,1),entries.map { it.level });assertEquals(listOf(9,13,21),entries.map { it.page })
    }
    @Test fun invalidRowsAbortWholeImport() {
        for(text in listOf("1\t1\tA\n3\t2\tB","2\t1\tA","1 1 A","1\t31\tA","1\t0\tA","# 空白","1\t1\t")) {
            assertThrows(IllegalArgumentException::class.java) { OutlineImport.parse(text,30) }
        }
    }
    @Test fun collapseAndSearchWorkAcrossSubtrees() {
        val entries=listOf(OutlineEntry("a","A",1),OutlineEntry("b","B",2,level=2),OutlineEntry("c","C",3,level=3),OutlineEntry("d","D",4))
        assertEquals(listOf("a","d"),visibleOutline(entries,setOf("a"),"").map { it.id })
        assertEquals(listOf("c"),visibleOutline(entries,setOf("a"),"C").map { it.id })
    }
    @Test fun repeatedMergesDeduplicateWithoutOrphaningChildren() {
        val existing=listOf(OutlineEntry("a","A",1),OutlineEntry("b","B",2,level=2))
        val incoming=listOf(OutlineEntry("a2","A",1),OutlineEntry("b2","B",2,level=2),OutlineEntry("c","C",3,level=2),OutlineEntry("d","D",4))
        val merged=mergeOutline(existing,incoming)
        assertEquals(listOf("A","B","C","D"),merged.map { it.title })
        assertEquals(listOf(1,2,2,1),merged.map { it.level })
        assertEquals(merged,mergeOutline(merged,incoming))
    }
}
