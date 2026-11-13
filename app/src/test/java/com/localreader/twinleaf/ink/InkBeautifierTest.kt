package com.localreader.twinleaf.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class InkBeautifierTest {
    private fun point(x:Float,y:Float,time:Long=0,pressure:Float=.6f)=
        InkPoint(x,y,pressure,timeMillis=time,rawPressure=pressure)
    private fun stroke(points:List<InkPoint>)=InkStroke(points,0xFF223344.toInt(),.004f)

    @Test fun offLeavesOrdinaryStrokeUntouched() {
        val source=stroke(listOf(point(.1f,.2f),point(.2f,.25f),point(.3f,.2f)))
        assertEquals(source,beautifyInkStroke(source,InkBeautifyLevel.OFF,false,1000f,1000f))
    }

    @Test fun longNearlyStraightStrokeIsStraightenedWithoutMovingEndpoints() {
        val raw=listOf(
            point(.1f,.500f,0),point(.2f,.504f,20),point(.3f,.497f,40),point(.4f,.503f,60),
            point(.5f,.498f,80),point(.6f,.502f,100),point(.7f,.499f,120),point(.8f,.501f,140),point(.9f,.500f,160))
        val result=beautifyInkStroke(stroke(raw),InkBeautifyLevel.STANDARD,true,1000f,1000f,
            InkBeautifyBounds(0f,0f,1f,1f))
        assertTrue(result.autoStraightened)
        assertEquals(raw,result.rawPoints)
        assertEquals(raw.first().x,result.points.first().x,0f)
        assertEquals(raw.last().x,result.points.last().x,0f)
        val first=result.points.first();val last=result.points.last()
        result.points.forEach { p ->
            val cross=(p.x-first.x)*(last.y-first.y)-(p.y-first.y)*(last.x-first.x)
            assertTrue(abs(cross)<1e-5f)
        }
    }

    @Test fun threeSamplesAtAbout48PixelsAreEnoughForStraightRecognition() {
        val raw=listOf(point(0f,0f),point(24f,2f),point(48f,-1f))
        val result=beautifyInkStroke(stroke(raw),InkBeautifyLevel.STANDARD,true)

        assertTrue(result.autoStraightened)
        assertEquals(raw.first().x,result.points.first().x,0f)
        assertEquals(raw.first().y,result.points.first().y,0f)
        assertEquals(raw.last().x,result.points.last().x,0f)
        assertEquals(raw.last().y,result.points.last().y,0f)
    }

    @Test fun sensitiveModeAcceptsShorterRuleWithoutChangingStandardBoundary() {
        val raw=listOf(point(0f,0f),point(21f,1f),point(42f,0f))
        assertFalse(looksLikeStraightLine(raw,level=InkBeautifyLevel.STANDARD,
            sensitivity=StraightenSensitivity.STANDARD))
        assertTrue(looksLikeStraightLine(raw,level=InkBeautifyLevel.STANDARD,
            sensitivity=StraightenSensitivity.SENSITIVE))
    }

    @Test fun mildJitterAndOneIsolatedOutlierDoNotFailByMaximumDeviationAlone() {
        val raw=(0..20).map { index ->
            val x=index*10f
            val y=when {
                index==10 -> 20f
                index%2==0 -> 3f
                else -> -3f
            }
            point(x,y,index.toLong())
        }
        val result=beautifyInkStroke(stroke(raw),InkBeautifyLevel.STANDARD,true)

        assertTrue(result.autoStraightened)
    }

    @Test fun obviousReversalAndClosedLoopAreRejected() {
        val reversal=listOf(
            point(0f,0f),point(40f,1f),point(80f,-1f),point(45f,1f),point(70f,0f),
        )
        val closed=listOf(
            point(0f,0f),point(40f,0f),point(40f,40f),point(0f,40f),point(0f,0f),
        )

        assertFalse(beautifyInkStroke(stroke(reversal),InkBeautifyLevel.STANDARD,true).autoStraightened)
        assertFalse(beautifyInkStroke(stroke(closed),InkBeautifyLevel.STANDARD,true).autoStraightened)
    }

    @Test fun shortHandwritingStrokeIsNotReinterpretedAsLine() {
        val raw=listOf(point(.10f,.5f),point(.11f,.501f),point(.12f,.499f),point(.14f,.5f))
        val result=beautifyInkStroke(stroke(raw),InkBeautifyLevel.OFF,true,1000f,1000f)
        assertFalse(result.autoStraightened)
        assertEquals(raw,result.points)
    }

    @Test fun cornerAndHookAreProtectedFromStraightRecognition() {
        val raw=listOf(point(.1f,.1f),point(.3f,.1f),point(.5f,.1f),point(.5f,.3f),point(.5f,.5f))
        val result=beautifyInkStroke(stroke(raw),InkBeautifyLevel.STANDARD,true,1000f,1000f)
        assertFalse(result.autoStraightened)
        assertNotNull(result.rawPoints)
        assertEquals(raw.first().x,result.points.first().x,0f)
        assertEquals(raw.last().y,result.points.last().y,0f)
    }

    @Test fun outputStaysInsidePageBoundsWhileRawSamplesRemainAvailable() {
        val raw=listOf(point(-.05f,.4f),point(.25f,.4f),point(.5f,.4f),point(.75f,.4f),point(1.05f,.4f))
        val result=beautifyInkStroke(stroke(raw),InkBeautifyLevel.STANDARD,true,1000f,1000f,
            InkBeautifyBounds(0f,0f,1f,1f))
        assertTrue(result.points.all { it.x in 0f..1f && it.y in 0f..1f })
        assertEquals(raw,result.rawPoints)
    }

    @Test fun smoothingKeepsFirstAndLastPointAndFinitePressure() {
        val raw=listOf(point(.1f,.3f,pressure=.2f),point(.2f,.34f,pressure=8f),
            point(.3f,.31f,pressure=.1f),point(.4f,.36f,pressure=.7f),point(.5f,.32f,pressure=.4f))
        val result=beautifyInkStroke(stroke(raw),InkBeautifyLevel.STRONG,false,1000f,1000f)
        assertEquals(raw.first().x,result.points.first().x,0f)
        assertEquals(raw.first().y,result.points.first().y,0f)
        assertEquals(raw.last().x,result.points.last().x,0f)
        assertEquals(raw.last().y,result.points.last().y,0f)
        assertTrue(result.points.all { it.pressure.isFinite() && it.pressure in 0f..10f })
    }
}
