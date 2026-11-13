package com.localreader.twinleaf.data

import com.localreader.twinleaf.ink.*
import org.junit.Assert.*
import org.junit.Test

class InkViewportTest {
    @Test fun pinchStartsAtOneAndKeepsFocusPointAnchored() {
        val next=transformInkViewport(InkViewport(),1000f,800f,400f,300f,400f,300f,2f)
        assertEquals(2f,next.zoom,0f);assertEquals(100f,next.panX,0f);assertEquals(100f,next.panY,0f)
        assertEquals(400f,500f+next.panX+(400f-500f)*next.zoom,0f)
    }
    @Test fun minimumZoomReturnsToOriginAndMaximumIsClamped() {
        assertEquals(InkViewport(),transformInkViewport(InkViewport(2f,100f,80f),1000f,800f,400f,300f,400f,300f,.1f))
        assertEquals(3f,transformInkViewport(InkViewport(),1000f,800f,500f,400f,500f,400f,10f).zoom,0f)
    }
    @Test fun panClampsToDocumentEdgesAndInvalidScaleDoesNothing() {
        val old=InkViewport(2f)
        val next=transformInkViewport(old,1000f,800f,0f,0f,9000f,-9000f,1f)
        assertEquals(500f,next.panX,0f);assertEquals(-400f,next.panY,0f)
        assertEquals(old,transformInkViewport(old,1000f,800f,0f,0f,0f,0f,Float.NaN))
    }
}
