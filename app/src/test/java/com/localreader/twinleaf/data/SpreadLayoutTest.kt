package com.localreader.twinleaf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadLayoutTest {
    @Test
    fun coverIsAloneOnRight() {
        assertEquals(PagePair(null, 0), SpreadLayout.pagesFor(0, 5))
    }

    @Test
    fun pagesAfterCoverArePaired() {
        assertEquals(PagePair(1, 2), SpreadLayout.pagesFor(1, 5))
        assertEquals(PagePair(3, 4), SpreadLayout.pagesFor(2, 5))
    }

    @Test
    fun finalEvenNumberedPageLeavesRightSideBlank() {
        assertEquals(3, SpreadLayout.spreadCount(4))
        assertEquals(PagePair(3, null), SpreadLayout.pagesFor(2, 4))
    }

    @Test
    fun emptyDocumentHasNoSpread() {
        assertEquals(0, SpreadLayout.spreadCount(0))
        assertEquals(PagePair(null, null), SpreadLayout.pagesFor(0, 0))
    }

    @Test
    fun adaptiveLayoutKeepsWidePagesWhole() {
        val groups = SpreadLayout.groups(
            pageCount = 6,
            aspectRatios = listOf(.7f, .7f, .7f, 1.6f, .7f, .7f),
            mode = PdfGroupingMode.ADAPTIVE,
        )
        assertEquals(
            listOf(
                PdfPageGroup(null, 0),
                PdfPageGroup(1, 2),
                PdfPageGroup(null, 3),
                PdfPageGroup(4, 5),
            ),
            groups,
        )
    }

    @Test
    fun adaptiveLayoutDoesNotLosePortraitPageBesideWidePage() {
        val groups = SpreadLayout.groups(
            pageCount = 4,
            aspectRatios = listOf(.7f, .7f, 1.5f, .7f),
            mode = PdfGroupingMode.ADAPTIVE,
        )
        assertEquals(listOf(0, 1, 2, 3), groups.flatMap { it.pages })
        assertEquals(2, SpreadLayout.groupContaining(groups, 2))
    }

    @Test
    fun singleLayoutMapsOnePhysicalPageToOneCard() {
        val groups = SpreadLayout.groups(3, listOf(.7f, 1.8f, .7f), PdfGroupingMode.SINGLE)
        assertEquals(
            listOf(PdfPageGroup(null, 0), PdfPageGroup(null, 1), PdfPageGroup(null, 2)),
            groups,
        )
    }
}
