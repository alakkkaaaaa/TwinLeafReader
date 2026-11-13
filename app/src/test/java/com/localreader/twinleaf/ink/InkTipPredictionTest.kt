package com.localreader.twinleaf.ink

import org.junit.Assert.*
import org.junit.Test

class InkTipPredictionTest {
    private fun line() = listOf(InkPoint(0f,0f,.6f,timeMillis=100),
        InkPoint(4f,0f,.6f,timeMillis=104), InkPoint(8f,0f,.6f,timeMillis=108))
    private fun predict(points: List<InkPoint>, now: Long=108, scale: Float=1f, hz:Float=120f) =
        InkTipPrediction.predict(points,now,scale,scale,12f,hz)

    @Test fun predictionIsTemporaryBoundedAndPreservesAllRealSamples() {
        val source=line();val before=source.toList()
        val tip=predict(source)!!
        assertTrue(tip.x>source.last().x)
        assertTrue(tip.x-source.last().x<=12f)
        assertEquals(source.last().pressure,tip.pressure,0f)
        assertEquals(before,source)
        assertNull(predict(source,133))
        assertNull(predict(source,107))
    }

    @Test fun cornersReversalsPressureCollapseAndStaleInputNeverPredict() {
        val source=line()
        assertNull(predict(source.dropLast(1)+source.last().copy(x=4f,y=4f)))
        assertNull(predict(source.dropLast(1)+source.last().copy(x=0f)))
        assertNull(predict(source.dropLast(1)+source.last().copy(rawPressure=.05f)))
        assertNull(predict(source.dropLast(1)+source.last().copy(x=100f)))
        assertNull(predict(source.dropLast(1)+source.last().copy(timeMillis=104)))
        assertNull(predict(source.take(2)))
    }

    @Test fun zoomAndHighRefreshKeepScreenSpacePredictionBounded() {
        val tip=predict(line(),scale=8f)!!
        assertEquals(12f,(tip.x-8f)*8f,.0001f)
        assertTrue(predict(line(),hz=240f)!!.x<predict(line(),hz=60f)!!.x)
        assertNull(predict(line(),scale=Float.NaN))
        assertNull(predict(line(),scale=0f))
        assertNotNull(predict(line().map { it.copy(pressure=0f,rawPressure=0f) }))
    }

    @Test fun chunkBoundariesCoverEachSegmentExactlyOnce() {
        for(size in listOf(0,1,2,128,129,130,256,257,258,20000,50000)) {
            val segments=ArrayList<Int>()
            for(chunk in 0 until LiveStrokeChunks.count(size)) {
                val first=chunk*LiveStrokeChunks.SEGMENTS
                val last=minOf(first+LiveStrokeChunks.SEGMENTS,size-1)
                for(i in first+1..last) segments.add(i)
            }
            assertEquals((1 until size).toList(),segments)
        }
    }
}
