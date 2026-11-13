package com.localreader.twinleaf.data

import com.localreader.twinleaf.ink.BoundedInkHistory
import com.localreader.twinleaf.ink.InkPoint
import com.localreader.twinleaf.ink.InkStroke
import org.junit.Assert.*
import org.junit.Test

class BoundedInkHistoryTest {
    private fun stroke(x:Int)=InkStroke(listOf(InkPoint(x.toFloat(),0f)),0,1f)
    @Test fun oldestOperationsExpireWithoutRemovingDrawing() {
        val history=BoundedInkHistory(maxOperations=3)
        var source=emptyList<InkStroke>()
        repeat(5) { val next=source+stroke(it);history.record(0,source,next);source=next }
        repeat(3) { source=history.undo { source }!!.second }
        assertEquals(2,source.size);assertFalse(history.canUndo)
        repeat(3) { source=history.redo { source }!!.second }
        assertEquals(5,source.size)
    }
    @Test fun eraserReplacementAndMultiplePagesUndoExactly() {
        val history=BoundedInkHistory()
        val original=listOf(stroke(1),stroke(2),stroke(3))
        val erased=listOf(original[0],stroke(20),stroke(21),original[2])
        val pages=mutableMapOf(0 to original,1 to emptyList<InkStroke>())
        history.record(0,original,erased);pages[0]=erased
        val pageOne=listOf(stroke(4));history.record(1,emptyList(),pageOne);pages[1]=pageOne
        history.undo { pages[it].orEmpty() }!!.let { pages[it.first]=it.second }
        history.undo { pages[it].orEmpty() }!!.let { pages[it.first]=it.second }
        assertEquals(original,pages[0]);assertEquals(emptyList<InkStroke>(),pages[1])
        history.redo { pages[it].orEmpty() }!!.let { pages[it.first]=it.second }
        assertEquals(erased,pages[0])
    }
    @Test fun memoryLimitDropsOversizedEditAndNewEditClearsRedo() {
        val history=BoundedInkHistory(maxBytes=500)
        val one=listOf(stroke(1));history.record(0,emptyList(),one)
        history.undo { one }
        history.record(0,emptyList(),listOf(stroke(2)))
        assertFalse(history.canRedo);assertTrue(history.canUndo)
        history.record(0,one,List(10) { stroke(it) })
        assertFalse(history.canUndo);assertFalse(history.canRedo)
    }
}
