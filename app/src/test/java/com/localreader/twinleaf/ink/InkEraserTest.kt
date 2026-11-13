package com.localreader.twinleaf.ink

import org.junit.Assert.*
import org.junit.Test

class InkEraserTest {
    private fun point(x:Float,y:Float=.5f,pressure:Float=.5f,time:Long=0)=
        InkPoint(x,y,pressure,tilt=.2f,orientation=.4f,timeMillis=time,rawPressure=pressure)
    private fun line(vararg points:InkPoint)=InkStroke(points.toList(),0xFF123456.toInt(),.002f,
        brush=InkBrush.FOUNTAIN,opacity=.6f,curve=PressureCurve.SOFT)

    @Test fun roundEraserSplitsOnlyTheTouchedMiddle() {
        val stroke=line(point(.1f,time=0),point(.9f,pressure=.9f,time=800))
        val session=InkEraseSession(listOf(stroke),1000f,1000f,20f)
        val result=session.eraseAt(.5f,.5f)!!
        assertEquals(2,result.size)
        assertTrue(result[0].points.last().x in .47f.. .49f)
        assertTrue(result[1].points.first().x in .51f.. .53f)
        assertEquals(stroke.color,result[0].color)
        assertEquals(stroke.brush,result[1].brush)
        assertEquals(stroke.opacity,result[0].opacity,0f)
        assertEquals(stroke.curve,result[1].curve)
        assertTrue(result[0].points.last().pressure>stroke.points.first().pressure)
        assertTrue(result[0].points.last().timeMillis in 300..500)
    }

    @Test fun movementSweepsAContinuousCapsule() {
        val stroke=line(point(.1f),point(.9f))
        val session=InkEraseSession(listOf(stroke),1000f,1000f,16f)
        assertNull(session.eraseAt(.5f,.2f))
        val result=session.eraseAt(.5f,.8f)!!
        assertEquals(2,result.size)
        assertTrue(result[0].points.last().x<.5f)
        assertTrue(result[1].points.first().x>.5f)
    }

    @Test fun untouchedInkIsReturnedWithoutPageRewrite() {
        val stroke=line(point(.1f,.1f),point(.2f,.1f))
        val session=InkEraseSession(listOf(stroke),1000f,1000f,12f)
        assertNull(session.eraseAt(.8f,.8f))
        assertEquals(listOf(stroke),session.snapshot())
    }

    @Test fun pointerOutsidePageDoesNotClampAndEraseTheEdge() {
        val edge=line(point(0f,.4f),point(.1f,.4f))
        val session=InkEraseSession(listOf(edge),1000f,1000f,12f)
        assertNull(session.eraseAt(-.2f,.4f))
        assertEquals(listOf(edge),session.snapshot())
    }

    @Test fun repeatedPassesSplitExistingFragmentsWithoutDeletingTheRest() {
        val stroke=line(point(.1f),point(.9f))
        val first=InkEraseSession(listOf(stroke),1000f,1000f,12f).eraseAt(.3f,.5f)!!
        val result=InkEraseSession(first,1000f,1000f,12f).eraseAt(.7f,.5f)!!
        assertEquals(3,result.size)
        assertEquals(.1f,result.first().points.first().x,0f)
        assertEquals(.9f,result.last().points.last().x,0f)
    }

    @Test fun onePointAndLegacyInkAreSupported() {
        val dot=InkStroke(listOf(point(.5f)),0xFF000000.toInt(),.003f)
        val oldLine=InkStroke(listOf(point(.1f,.1f),point(.3f,.1f)),0xFF000000.toInt(),.003f)
        val session=InkEraseSession(listOf(dot,oldLine),1000f,1000f,8f)
        val result=session.eraseAt(.5f,.5f)!!
        assertEquals(listOf(oldLine),result)
        assertEquals(InkBrush.LEGACY,result.single().brush)
    }
}
