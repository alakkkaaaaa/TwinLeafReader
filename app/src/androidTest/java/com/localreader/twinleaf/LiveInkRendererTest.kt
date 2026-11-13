package com.localreader.twinleaf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.RenderNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.localreader.twinleaf.ink.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveInkRendererTest {
    @Test @SdkSuppress(minSdkVersion=29)
    fun longLiveStrokeRecordsOnlyChangedTailAndClearsBetweenGestures() {
        val samples=MutableList(5000) { InkPoint(10f+it*.08f,80f,.6f,timeMillis=it.toLong()) }
        val live=LiveInkRenderer()
        live.begin(InkStroke(samples,Color.BLUE,4f,brush=InkBrush.FOUNTAIN))
        live.predictionAllowed=false
        val root=RenderNode("test-live-root").apply { setPosition(0,0,512,256) }
        fun draw() {
            val canvas=root.beginRecording(512,256)
            try { live.draw(canvas) } finally { root.endRecording() }
        }
        draw();val cold=live.recordedSegments
        assertEquals(4999L,cold)
        repeat(4) { draw() }
        assertEquals(cold,live.recordedSegments)
        samples.add(InkPoint(410f,80f,.6f,timeMillis=5000))
        draw()
        assertTrue(live.recordedSegments-cold in 1L..128L)
        live.clear();draw()
        assertEquals(0L,live.recordedSegments)
        root.discardDisplayList()
    }

    @Test fun softwareFallbackAndGrowingHighlighterMatchSavedStrokeWithoutAlphaSeams() {
        for(brush in InkBrush.entries) {
            val samples=ArrayList<InkPoint>()
            val stroke=InkStroke(samples,Color.BLUE,.02f,highlight=brush==InkBrush.HIGHLIGHTER,
                brush=brush,opacity=if(brush==InkBrush.HIGHLIGHTER) .3f else 1f)
            val live=LiveInkRenderer()
            val rect=RectF(0f,0f,512f,256f)
            live.begin(stroke,rect);live.predictionAllowed=false
            val actual=Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888)
            val expected=Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888)
            for(batch in 0..3) {
                repeat(100) { i -> samples.add(InkPoint(.1f+(batch*100+i)*.0015f,.5f,.6f)) }
                actual.eraseColor(Color.TRANSPARENT);expected.eraseColor(Color.TRANSPARENT)
                live.draw(Canvas(actual));drawInk(Canvas(expected),rect,listOf(stroke))
                assertTrue("$brush growing stroke raster mismatch",actual.sameAs(expected))
            }
            live.clear();actual.eraseColor(Color.TRANSPARENT);live.draw(Canvas(actual))
            assertEquals(Color.TRANSPARENT,actual.getPixel(150,128))
            actual.recycle();expected.recycle()
        }
    }
}
