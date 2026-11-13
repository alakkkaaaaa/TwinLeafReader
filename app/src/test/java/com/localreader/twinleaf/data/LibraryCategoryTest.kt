package com.localreader.twinleaf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCategoryTest {
    @Test fun legacyNamesResolveToStableIds() {
        assertEquals(TEXTBOOK_CATEGORY_ID, legacyCategoryId("教科书", DEFAULT_LIBRARY_CATEGORIES))
        assertEquals(UNCATEGORIZED_CATEGORY_ID, legacyCategoryId("未分类", DEFAULT_LIBRARY_CATEGORIES))
        assertEquals(NOVEL_CATEGORY_ID, legacyCategoryId(NOVEL_CATEGORY_ID, DEFAULT_LIBRARY_CATEGORIES))
    }

    @Test fun normalizationKeepsOneProtectedFallback() {
        val result = normalizedCategories(listOf(
            LibraryCategory("a", "自定义"),
            LibraryCategory("a", "重复 ID"),
            LibraryCategory("b", "自定义"),
        ))
        assertEquals(listOf("a", UNCATEGORIZED_CATEGORY_ID), result.map { it.id })
        assertTrue(result.last().isProtected)
    }

    @Test fun reorderPreservesUnmentionedCategories() {
        val result = reorderedCategories(DEFAULT_LIBRARY_CATEGORIES, listOf(COMIC_CATEGORY_ID, TEXTBOOK_CATEGORY_ID))
        assertEquals(listOf(COMIC_CATEGORY_ID, TEXTBOOK_CATEGORY_ID, NOVEL_CATEGORY_ID, UNCATEGORIZED_CATEGORY_ID), result.map { it.id })
        assertFalse(result.first().isProtected)
    }
}
