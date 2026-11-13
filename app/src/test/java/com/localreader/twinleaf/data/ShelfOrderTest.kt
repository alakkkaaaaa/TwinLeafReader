package com.localreader.twinleaf.data

import org.junit.Assert.*
import org.junit.Test

class ShelfOrderTest {
    @Test fun mixedKeysNormalizeWithoutCollisionsAndAppendNewItems() {
        assertEquals(listOf("book:a", "folder:a", "folder:b", "book:c"),
            normalizedShelfOrder(listOf("book:a", "folder:a", "gone", "book:a"), listOf("a", "b"), listOf("a", "c")))
    }
    @Test fun folderCanMoveAcrossBooksWithoutCreatingMembership() {
        assertEquals(listOf("book:a", "book:b", "folder:f"),
            moveShelfItem(listOf("folder:f", "book:a", "book:b"), "folder:f", "book:b"))
    }
    @Test fun folderEdgeInsertsBeforeOrAfterWithoutLosingEntries() {
        val keys = listOf("folder:f", "book:a", "book:b")
        assertEquals(listOf("book:b", "folder:f", "book:a"), insertShelfItem(keys, "book:b", "folder:f", false))
        assertEquals(listOf("folder:f", "book:b", "book:a"), insertShelfItem(keys, "book:b", "folder:f", true))
        assertEquals(keys, insertShelfItem(keys, "folder:f", "folder:f", true))
    }
    @Test fun filteredMixedOrderKeepsHiddenSlots() {
        assertEquals(listOf("book:b", "book:hidden", "folder:f"),
            reorderVisibleSlots(listOf("folder:f", "book:hidden", "book:b"), listOf("book:b", "folder:f")))
    }
    @Test fun insertionClosesOldSlot() {
        assertEquals(listOf("b", "c", "a", "d"), moveShelfItem(listOf("a", "b", "c", "d"), "a", "c"))
        assertEquals(listOf("d", "a", "b", "c"), moveShelfItem(listOf("a", "b", "c", "d"), "d", "a"))
    }
    @Test fun filteredReorderPreservesHiddenSlots() {
        assertEquals(listOf("c", "hidden", "a", "tail"), reorderVisibleSlots(listOf("a", "hidden", "c", "tail"), listOf("c", "a")))
    }
    @Test fun duplicatesAndStaleTargetsAreIgnored() {
        assertEquals(listOf("b", "a"), reorderVisibleSlots(listOf("a", "b"), listOf("b", "b", "gone", "a")))
        assertEquals(listOf("a"), moveShelfItem(listOf("a"), "a", "gone"))
    }
    @Test fun emptyDropDoesNotEraseBooks() {
        assertEquals(listOf("a", "b"), reorderVisibleSlots(listOf("a", "b"), emptyList()))
    }
    @Test fun manualRootOrderSurvivesReadingAndAppendsNewBooks() {
        val a = LibraryBook("a", "a", "A", "PDF", null, lastOpenedAt = 100, importedAt = 1)
        val b = a.copy(id = "b", lastOpenedAt = 200, importedAt = 2)
        val c = a.copy(id = "c", lastOpenedAt = 300, importedAt = 3)
        assertEquals(listOf("b", "a", "c"), orderedRootBooks(listOf(a, b, c), listOf("b", "a")).map { it.id })
        assertEquals(listOf("c", "b", "a"), orderedRootBooks(listOf(a, b, c), null).map { it.id })
    }
}
