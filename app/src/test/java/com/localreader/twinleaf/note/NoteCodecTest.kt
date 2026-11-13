package com.localreader.twinleaf.note

import com.localreader.twinleaf.ink.InkBrush
import com.localreader.twinleaf.ink.InkPoint
import com.localreader.twinleaf.ink.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteCodecTest {
    @Test fun validationKeepsWorldCoordinatesAndViewport() {
        val stroke=InkStroke(listOf(InkPoint(-1200.5f,840.25f,.64f,timeMillis=42),InkPoint(9000f,-75f,.8f)),
            0xFF243A68.toInt(),3.5f,brush=InkBrush.FOUNTAIN)
        val source=NoteSnapshot(listOf(stroke),NoteBackground.DOT_GRID,NoteViewport(-44f,888f,2.25f))
        assertEquals(source,NoteCodec.validate(source))
    }

    @Test fun boundsIncludeStrokeWidthPadding() {
        val stroke=InkStroke(listOf(InkPoint(-10f,5f),InkPoint(30f,25f)),0,4f)
        val bounds=stroke.noteBounds()
        assertTrue(bounds.left < -10f && bounds.top < 5f)
        assertTrue(bounds.right > 30f && bounds.bottom > 25f)
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsUnboundedCoordinates() {
        NoteCodec.validate(NoteSnapshot(listOf(InkStroke(listOf(InkPoint(20_000_000f,0f)),0,2f))))
    }
}
