package com.localreader.twinleaf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localreader.twinleaf.ink.HistoricalInkCache
import com.localreader.twinleaf.ink.InkPoint
import com.localreader.twinleaf.ink.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoricalInkCacheTest {
    private fun stroke(x:Float,color:Int=Color.RED,opacity:Float=1f)=
        InkStroke(listOf(InkPoint(x,64f)),color,20f,highlight=opacity<1f,opacity=opacity)

    private fun render(target:Canvas,stroke:InkStroke,themed:Boolean=false) {
        val p=stroke.points.first()
        val paint=Paint().apply { color=if(themed) Color.GREEN else stroke.color;alpha=(stroke.opacity*255).toInt() }
        target.drawRect(p.x-10,p.y-10,p.x+10,p.y+10,paint)
    }

    private fun draw(cache:HistoricalInkCache,bitmap:Bitmap,strokes:List<InkStroke>,scale:Float=1f,themed:Boolean=false) {
        bitmap.eraseColor(Color.TRANSPARENT)
        cache.draw(Canvas(bitmap),strokes,RectF(0f,0f,512f,256f),scale,styleKey=themed,
            bounds={ val p=it.points.first();RectF(p.x-11,p.y-11,p.x+11,p.y+11) },
            render={ canvas,stroke -> render(canvas,stroke,themed) })
    }

    private fun assertMatchesDirect(bitmap:Bitmap,strokes:List<InkStroke>,themed:Boolean=false) {
        val direct=Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(direct)
        strokes.forEach { render(canvas,it,themed) }
        // Interior pixels avoid raster filtering differences at shape and tile edges.
        for(x in listOf(64,96,320,480)) assertEquals("pixel at $x",direct.getPixel(x,64),bitmap.getPixel(x,64))
        direct.recycle()
    }

    @Test fun appendPromotesOnlyTouchedTilesAndPreservesOpaqueAndHighlighterPixels() {
        val cache=HistoricalInkCache(recentCount=1)
        val bitmap=Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888)
        val source=listOf(stroke(64f),stroke(320f,Color.BLUE,.3f),stroke(96f,Color.BLUE,.3f))
        draw(cache,bitmap,source);assertMatchesDirect(bitmap,source)
        val cold=cache.renderedTileCount
        val appended=source+stroke(480f)
        draw(cache,bitmap,appended);assertMatchesDirect(bitmap,appended)
        assertEquals(cold+1,cache.renderedTileCount)
        draw(cache,bitmap,appended);assertEquals(cold+1,cache.renderedTileCount)
        assertEquals(3,source.size);assertEquals(4,appended.size)
        assertTrue(appended[0]===source[0]);assertTrue(appended[2]===source[2])
        cache.clear();bitmap.recycle()
    }

    @Test fun erasingEditingUndoZoomAndThemeNeverLeaveGhostPixels() {
        val cache=HistoricalInkCache(recentCount=1)
        val bitmap=Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888)
        val original=listOf(stroke(64f),stroke(320f,Color.BLUE,.3f),stroke(96f))
        draw(cache,bitmap,original);assertMatchesDirect(bitmap,original)
        val erased=original.drop(1)
        draw(cache,bitmap,erased);assertMatchesDirect(bitmap,erased)
        assertEquals(Color.TRANSPARENT,bitmap.getPixel(64,64))
        val edited=listOf(stroke(480f,Color.BLUE,.3f),erased.last())
        draw(cache,bitmap,edited);assertMatchesDirect(bitmap,edited)
        assertEquals(Color.TRANSPARENT,bitmap.getPixel(320,64))
        draw(cache,bitmap,original);assertMatchesDirect(bitmap,original)
        val beforeZoom=cache.renderedTileCount
        draw(cache,bitmap,original,scale=2f);assertMatchesDirect(bitmap,original)
        assertTrue(cache.renderedTileCount>beforeZoom)
        val beforeTheme=cache.renderedTileCount
        draw(cache,bitmap,original,scale=2f,themed=true);assertMatchesDirect(bitmap,original,themed=true)
        assertTrue(cache.renderedTileCount>beforeTheme)
        draw(cache,bitmap,original);assertMatchesDirect(bitmap,original)
        cache.clear();bitmap.recycle()
    }

    @Test fun largeDenseViewportKeepsWarmHistoricalTiles() {
        val cache=HistoricalInkCache()
        val bitmap=Bitmap.createBitmap(3390,2400,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val paint=Paint().apply { color=0xff123456.toInt() }
        // More than 100 distinct native-resolution tiles, plus the recent tail.
        val strokes=buildList {
            for(y in 0..9) for(x in 0..13) add(InkStroke(listOf(InkPoint(x*256f,y*256f)),paint.color,20f))
            repeat(24) { add(InkStroke(listOf(InkPoint(100f,100f)),paint.color,20f)) }
        }
        val visible=RectF(0f,0f,3390f,2400f)
        fun draw(scale:Float=1f) { cache.draw(canvas,strokes,visible,scale,
            bounds={ val p=it.points.first();RectF(p.x-20,p.y-20,p.x+20,p.y+20) },
            render={ target,stroke -> val p=stroke.points.first();target.drawCircle(p.x,p.y,10f,paint) }) }
        draw();val cold=cache.renderedTileCount
        assertTrue(cold>0)
        repeat(3) { draw() }
        assertEquals(cold,cache.renderedTileCount)
        draw(1.01f)
        assertEquals(cold,cache.renderedTileCount)
        cache.clear();bitmap.recycle()
    }
}
