package com.localreader.twinleaf

import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.twinleaf.ink.InkBeautifyLevel
import com.localreader.twinleaf.ink.InkOverlayView
import com.localreader.twinleaf.ink.InkStore
import com.localreader.twinleaf.ink.InkStroke
import com.localreader.twinleaf.ink.InkTool
import com.localreader.twinleaf.note.InfiniteNoteView
import com.localreader.twinleaf.note.NoteStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic, unknown devices exercise the actual Android view event fallback in both canvases. */
@RunWith(AndroidJUnit4::class)
class StylusBoundaryRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class CanvasProbe(
        val name: String,
        val view: View,
        val strokes: () -> List<InkStroke>,
        val drawing: () -> Boolean,
        val screenX: (Float) -> Float,
    ) {
        private var time = SystemClock.uptimeMillis()
        var gesture = time
        fun send(action: Int, x: Float, pressure: Float = 0.6f, device: Int = 913701,
                 pointer: Int = 7, downTime: Long? = null) {
            time += 20
            if (action == MotionEvent.ACTION_DOWN) gesture = time
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = pointer; toolType = MotionEvent.TOOL_TYPE_STYLUS
            })
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x; y = 400f; this.pressure = pressure; size = 0.01f
            })
            val event = MotionEvent.obtain(downTime ?: gesture, time, action, 1, properties,
                coordinates, 0, 0, 1f, 1f, device, 0, InputDevice.SOURCE_STYLUS, 0)
            try {
                if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER)
                    view.dispatchGenericMotionEvent(event)
                else view.dispatchTouchEvent(event)
            } finally { event.recycle() }
        }
        fun start(pressure: Float = 0.6f) {
            send(MotionEvent.ACTION_DOWN, 100f, pressure)
            send(MotionEvent.ACTION_MOVE, 120f, pressure)
            send(MotionEvent.ACTION_MOVE, 140f, pressure)
            assertTrue("$name must own an active stroke", drawing())
        }
        fun assertCount(expected: Int) = assertEquals(name, expected, strokes().size)
        fun assertRange(index: Int, low: Float, high: Float) {
            val xs = strokes()[index].points.map { screenX(it.x) }
            assertTrue("$name stroke $index must have points", xs.isNotEmpty())
            assertTrue("$name bridged outside $low..$high: $xs", xs.all { it >= low - 0.1f && it <= high + 0.1f })
        }
    }

    private fun bothCanvases(block: (CanvasProbe) -> Unit) {
        lateinit var ink: InkStore
        lateinit var note: NoteStore
        instrumentation.runOnMainSync {
            val id = "boundary-${System.nanoTime()}"
            ink = InkStore.get(context, "$id-pdf")
            note = NoteStore.get(context, "$id-note")
        }
        // Never block the main thread: store initialization resumes there after disk IO.
        val deadline = SystemClock.uptimeMillis() + 15000
        while (true) {
            var ready = false
            instrumentation.runOnMainSync { ready = ink.ready && note.ready }
            if (ready) break
            if (SystemClock.uptimeMillis() >= deadline) fail("Stores failed to initialize")
            SystemClock.sleep(25)
        }
        instrumentation.runOnMainSync {
            assertNull(ink.failure)
            assertNull(note.failure)
            ink.choose(InkTool.PEN)
            ink.updateBeautifyLevel(InkBeautifyLevel.OFF)
            ink.updateAutoStraighten(false)
            note.tool = InkTool.PEN
            note.updateBeautifyLevel(InkBeautifyLevel.OFF)
            note.updateAutoStraighten(false)
            val pdf = InkOverlayView(context).apply {
                store = ink; layout(0, 0, 1000, 1500)
                pageRects = mapOf(0 to RectF(0f, 0f, 1000f, 1500f))
            }
            val canvas = InfiniteNoteView(context).apply { store = note; layout(0, 0, 1000, 1500) }
            block(CanvasProbe("PDF", pdf, { ink.pages[0].orEmpty() }, { ink.drawing }, { it * 1000f }))
            block(CanvasProbe("Note", canvas, { note.strokes }, { note.drawing },
                { (it - note.viewport.centerX) * note.viewport.scale + 500f }))
        }
    }

    @Test fun hoverCommitsAndOrphanMoveCannotBridgeIntoNextStroke() = bothCanvases { probe ->
        probe.start()
        probe.send(MotionEvent.ACTION_HOVER_MOVE, 900f, 0f)
        assertFalse(probe.name, probe.drawing())
        probe.assertCount(1)
        probe.send(MotionEvent.ACTION_MOVE, 850f)
        probe.send(MotionEvent.ACTION_UP, 900f)
        probe.assertCount(1)
        probe.send(MotionEvent.ACTION_DOWN, 700f)
        probe.send(MotionEvent.ACTION_MOVE, 720f)
        probe.send(MotionEvent.ACTION_UP, 950f)
        probe.assertCount(2)
        probe.assertRange(0, 100f, 140f)
        probe.assertRange(1, 700f, 720f)
    }

    @Test fun cancelDiscardsAndOrphanMoveDoesNotRestart() = bothCanvases { probe ->
        probe.start()
        probe.send(MotionEvent.ACTION_CANCEL, 900f)
        assertFalse(probe.name, probe.drawing())
        probe.send(MotionEvent.ACTION_MOVE, 700f)
        probe.send(MotionEvent.ACTION_UP, 800f)
        probe.assertCount(0)
    }

    @Test fun pointerReuseWithDifferentDownTimeClosesPreviousGesture() = bothCanvases { probe ->
        probe.start()
        probe.send(MotionEvent.ACTION_MOVE, 900f, downTime = probe.gesture + 1)
        assertFalse(probe.name, probe.drawing())
        probe.send(MotionEvent.ACTION_MOVE, 950f)
        probe.send(MotionEvent.ACTION_UP, 950f)
        probe.assertCount(1)
        probe.assertRange(0, 100f, 140f)
    }

    @Test fun wrongDeviceMoveAndHoverCannotContaminateOwnedStroke() = bothCanvases { probe ->
        probe.start()
        probe.send(MotionEvent.ACTION_MOVE, 900f, device = 913702)
        probe.send(MotionEvent.ACTION_HOVER_MOVE, 900f, device = 913702)
        assertTrue(probe.name, probe.drawing())
        probe.send(MotionEvent.ACTION_MOVE, 160f)
        probe.send(MotionEvent.ACTION_UP, 950f)
        probe.assertCount(1)
        probe.assertRange(0, 100f, 160f)
    }

    @Test fun missingOwnedPointerClosesWithoutUsingOtherPointerCoordinates() = bothCanvases { probe ->
        probe.start()
        probe.send(MotionEvent.ACTION_MOVE, 900f, pointer = 9)
        assertFalse(probe.name, probe.drawing())
        probe.send(MotionEvent.ACTION_UP, 950f, pointer = 9)
        probe.assertCount(1)
        probe.assertRange(0, 100f, 140f)
    }

    @Test fun unknownDeviceZeroPressureWritesAndDisplacedUpIsExcluded() = bothCanvases { probe ->
        probe.start(pressure = 0f)
        probe.send(MotionEvent.ACTION_UP, 950f, pressure = 0f)
        probe.assertCount(1)
        probe.assertRange(0, 100f, 140f)
        assertTrue(probe.name, probe.strokes()[0].points.size >= 2)
    }

    @Test fun freshDownWithoutUpCommitsSeparateStrokes() = bothCanvases { probe ->
        probe.start()
        probe.send(MotionEvent.ACTION_DOWN, 700f)
        probe.send(MotionEvent.ACTION_MOVE, 720f)
        probe.send(MotionEvent.ACTION_UP, 950f)
        probe.assertCount(2)
        probe.assertRange(0, 100f, 140f)
        probe.assertRange(1, 700f, 720f)
    }
}
