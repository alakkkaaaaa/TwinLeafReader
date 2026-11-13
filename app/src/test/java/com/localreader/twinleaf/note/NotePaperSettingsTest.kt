package com.localreader.twinleaf.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePaperSettingsTest {
    @Test
    fun paperTypeEnumContainsAllSixPatterns() {
        assertEquals(
            setOf("BLANK", "LINED", "GRID", "DOT_GRID", "ENGINEERING", "ISOMETRIC"),
            NoteBackground.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun normalizedSettingsClampSpacingAndOpacityToTheirBoundaries() {
        val low = normalizedNotePaperSettings(NotePaperSettings(spacing = 1f, lineOpacity = -2f))
        val high = normalizedNotePaperSettings(NotePaperSettings(spacing = 100f, lineOpacity = 2f))

        assertEquals(16f, low.spacing)
        assertEquals(0f, low.lineOpacity)
        assertEquals(80f, high.spacing)
        assertEquals(1f, high.lineOpacity)
    }

    @Test
    fun normalizedSettingsUseDefaultsForNonFiniteValuesAndPreserveOtherFields() {
        val original = NotePaperSettings(
            pattern = NoteBackground.ENGINEERING,
            spacing = Float.NaN,
            lineOpacity = Float.POSITIVE_INFINITY,
            backgroundColor = 0xFF123456.toInt(),
            exportGuides = false,
        )

        val normalized = normalizedNotePaperSettings(original)

        assertEquals(32f, normalized.spacing)
        assertEquals(.18f, normalized.lineOpacity)
        assertEquals(NoteBackground.ENGINEERING, normalized.pattern)
        assertEquals(0xFF123456.toInt(), normalized.backgroundColor)
        assertFalse(normalized.exportGuides)
    }

    @Test
    fun lodStrideKeepsAtLeastMinimumScreenSpacing() {
        assertEquals(1, notePaperLodStride(spacing = 32f, scale = 1f))
        assertEquals(4, notePaperLodStride(spacing = 32f, scale = .1f))
        assertEquals(128f, notePaperLodSpacing(spacing = 32f, scale = .1f))
    }

    @Test
    fun lodStrideFallsBackToOneForInvalidWorldSpacingOrScale() {
        assertEquals(1, notePaperLodStride(spacing = Float.NaN, scale = 1f))
        assertEquals(1, notePaperLodStride(spacing = 32f, scale = 0f))
        assertEquals(1, notePaperLodStride(spacing = 32f, scale = Float.POSITIVE_INFINITY))
    }

    @Test
    fun engineeringMajorLinesAreEveryFifthIndexIncludingNegativeCoordinates() {
        assertTrue(isMajorGridLine(0))
        assertTrue(isMajorGridLine(5))
        assertTrue(isMajorGridLine(-5))
        assertTrue(isMajorGridLine(-10))
        assertFalse(isMajorGridLine(4))
        assertFalse(isMajorGridLine(-4))
    }
}
