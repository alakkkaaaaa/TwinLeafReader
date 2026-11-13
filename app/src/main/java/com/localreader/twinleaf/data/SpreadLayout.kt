package com.localreader.twinleaf.data

data class PagePair(val left: Int?, val right: Int?)

/** A displayed PDF unit. A unit is either one complete page or a conventional two-page spread. */
data class PdfPageGroup(val left: Int?, val right: Int?) {
    val pages: List<Int> get() = listOfNotNull(left, right)
    val isDouble: Boolean get() = left != null && right != null
    val focusedPage: Int get() = left ?: right ?: 0
}

/** Cover is alone on the right. Remaining pages are paired as an open paper book. */
object SpreadLayout {
    /** Pages wider than this are treated as landscape pages in adaptive mode. */
    const val LANDSCAPE_ASPECT_THRESHOLD = 1.15f

    fun spreadCount(pageCount: Int): Int =
        if (pageCount <= 0) 0 else 1 + pageCount / 2

    fun pagesFor(spread: Int, pageCount: Int): PagePair {
        require(spread >= 0) { "spread must be non-negative" }
        if (pageCount <= 0 || spread >= spreadCount(pageCount)) return PagePair(null, null)
        if (spread == 0) return PagePair(null, 0)

        val left = spread * 2 - 1
        val right = left + 1
        return PagePair(
            left = left.takeIf { it < pageCount },
            right = right.takeIf { it < pageCount },
        )
    }


    /**
     * Builds stable display groups from page aspect ratios after PDF rotation has been applied.
     *
     * - single: every PDF page is a card;
     * - double: retains the paper-book convention (cover on the right, then pairs);
     * - adaptive: the cover and wide pages stay single, consecutive portrait pages form spreads.
     *
     * The resulting groups always contain every physical PDF page exactly once, so page numbers,
     * reading progress and annotation coordinates never depend on the visual grouping.
     */
    fun groups(pageCount: Int, aspectRatios: List<Float>, mode: PdfGroupingMode): List<PdfPageGroup> {
        if (pageCount <= 0) return emptyList()
        require(aspectRatios.size >= pageCount) { "one aspect ratio is required for every page" }
        return when (mode) {
            PdfGroupingMode.SINGLE -> (0 until pageCount).map { PdfPageGroup(null, it) }
            PdfGroupingMode.DOUBLE -> (0 until spreadCount(pageCount)).map { index ->
                val pair = pagesFor(index, pageCount)
                PdfPageGroup(pair.left, pair.right)
            }
            PdfGroupingMode.ADAPTIVE -> buildList {
                add(PdfPageGroup(null, 0))
                var page = 1
                while (page < pageCount) {
                    val wide = aspectRatios[page] >= LANDSCAPE_ASPECT_THRESHOLD
                    val nextPortrait = page + 1 < pageCount &&
                        aspectRatios[page + 1] < LANDSCAPE_ASPECT_THRESHOLD
                    if (!wide && nextPortrait) {
                        add(PdfPageGroup(page, page + 1))
                        page += 2
                    } else {
                        // A wide page is kept whole. An unpaired portrait page also becomes a card.
                        add(PdfPageGroup(null, page))
                        page++
                    }
                }
            }
        }
    }

    fun groupContaining(groups: List<PdfPageGroup>, page: Int): Int =
        groups.indexOfFirst { page in it.pages }.takeIf { it >= 0 } ?: 0
}

enum class PdfGroupingMode { SINGLE, DOUBLE, ADAPTIVE }
