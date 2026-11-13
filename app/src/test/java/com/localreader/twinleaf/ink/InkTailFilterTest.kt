package com.localreader.twinleaf.ink

import org.junit.Assert.*
import org.junit.Test

class InkTailFilterTest {
    private fun point(x:Float,pressure:Float,time:Long)=InkPoint(x,0f,pressure,timeMillis=time,rawPressure=pressure)

    @Test fun shortLowPressureTailIsHeldAndDropped() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STANDARD)
        assertEquals(1,filter.offer(point(0f,.6f,0)).size)
        assertTrue(filter.offer(point(.01f,.01f,10)).isEmpty())
        assertTrue(filter.offer(point(.02f,.005f,20)).isEmpty())
        filter.discard()
        assertEquals(1,filter.offer(point(.03f,.6f,30)).size)
    }

    @Test fun recoveredAndSustainedLightWritingSurvive() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STANDARD)
        filter.offer(point(0f,.7f,0))
        assertTrue(filter.offer(point(.01f,.01f,10)).isEmpty())
        assertEquals(2,filter.offer(point(.02f,.5f,20)).size)
        assertTrue(filter.offer(point(.03f,.01f,30)).isEmpty())
        assertTrue(filter.offer(point(.04f,.01f,40)).isEmpty())
        assertTrue(filter.offer(point(.05f,.01f,50)).isEmpty())
        assertTrue(filter.offer(point(.06f,.01f,60)).isEmpty())
        assertEquals(5,filter.offer(point(.07f,.01f,70)).size)
    }

    @Test fun lightOrPressurelessDeviceStillDraws() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STEADY)
        assertEquals(1,filter.offer(point(0f,.02f,0)).size)
        assertEquals(1,filter.offer(point(.01f,.02f,10)).size)
        filter.begin(ReleaseSensitivity.STANDARD)
        assertEquals(1,filter.offer(point(0f,0f,0)).size)
        assertEquals(1,filter.offer(point(.01f,0f,10)).size)
        assertTrue(filter.acceptRelease(point(.02f,0f,20),point(.01f,0f,10),1000f,1000f))
    }

    @Test fun releaseNeedsPlausiblePressureTimeAndDistance() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STANDARD)
        val last=point(.1f,.5f,10)
        filter.offer(last)
        assertFalse(filter.acceptRelease(point(.3f,.5f,20),last,1000f,1000f))
        assertFalse(filter.acceptRelease(point(.11f,0f,20),last,1000f,1000f))
        assertFalse(filter.acceptRelease(point(.11f,.5f,120),last,1000f,1000f))
        assertTrue(filter.acceptRelease(point(.11f,.5f,20),last,1000f,1000f))
    }

    @Test fun everyDownResetsPriorPressureHistory() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STEADY)
        filter.offer(point(0f,.8f,0));filter.offer(point(.01f,.01f,10))
        filter.begin(ReleaseSensitivity.LIGHT)
        assertEquals(1,filter.offer(point(.5f,.01f,20)).size)
    }

    @Test fun releaseSensitivityChangesTailThreshold() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STEADY)
        filter.offer(point(0f,.8f,0))
        assertTrue(filter.offer(point(.01f,.1f,10)).isEmpty())
        filter.begin(ReleaseSensitivity.LIGHT)
        filter.offer(point(0f,.8f,0))
        assertEquals(1,filter.offer(point(.01f,.1f,10)).size)
    }

    private fun samples(vararg values:Pair<Float,Float>)=values.mapIndexed { index,(x,pressure) ->
        point(x,pressure,index*10L)
    }

    @Test fun acceptedModeratePressureJumpIsRemovedOnFinish() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STANDARD)
        val input=samples(0f to .8f,.01f to .8f,.02f to .7f,.03f to .6f,.2f to .12f)
        assertEquals(input.take(4),filter.finish(input,1000f,1000f))
    }

    @Test fun lowPressureDirectionReversalIsRemovedWithoutRequiringHighSpeed() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STANDARD)
        val input=samples(0f to .7f,.01f to .7f,.02f to .6f,.011f to .1f)
        assertEquals(input.take(3),filter.trim(input,1000f,1000f))
    }

    @Test fun lowPressureSharpSidewaysExitIsRemoved() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STANDARD)
        val input=samples(0f to .7f,.01f to .7f,.02f to .6f)+
            InkPoint(.02f,.015f,.1f,timeMillis=30,rawPressure=.1f)
        assertEquals(input.take(3),filter.trim(input,1000f,1000f))
    }

    @Test fun smoothLightFlickAndStandaloneLightWritingAreRetained() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STANDARD)
        val flick=samples(0f to .8f,.01f to .8f,.02f to .7f,.03f to .5f,.045f to .3f,
            .065f to .15f,.095f to .08f)
        assertEquals(flick,filter.trim(flick,1000f,1000f))
        val light=samples(0f to .03f,.01f to .025f,.02f to .02f,.04f to .01f)
        assertEquals(light,filter.trim(light,1000f,1000f))
    }

    @Test fun normalPressureCurveAndRealTurnAreRetained() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STANDARD)
        val curve=samples(0f to .6f,.01f to .6f,.02f to .6f)+
            InkPoint(.02f,.01f,.55f,timeMillis=30,rawPressure=.55f)
        assertEquals(curve,filter.trim(curve,1000f,1000f))
    }

    @Test fun pressurelessExtremeDriverJumpIsRemovedButOrdinaryWritingSurvives() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STANDARD)
        val ordinary=samples(0f to 0f,.01f to 0f,.02f to 0f,.03f to 0f)
        assertEquals(ordinary,filter.trim(ordinary,1000f,1000f))
        val jump=ordinary+point(.3f,0f,40)
        assertEquals(ordinary,filter.trim(jump,1000f,1000f))
        val constant=ordinary.map { it.copy(pressure=1f,rawPressure=1f) }
        assertEquals(constant,filter.trim(constant+point(.3f,1f,40),1000f,1000f))
    }

    @Test fun reviewOnlyTouchesLastWindowAndKeepsShortStrokes() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.LIGHT)
        val oldJump=listOf(point(0f,.8f,0),point(.01f,.8f,10),point(.2f,.1f,20),
            point(.21f,.8f,200),point(.22f,.8f,210),point(.23f,.8f,220))
        assertEquals(oldJump,filter.trim(oldJump,1000f,1000f))
        val tap=listOf(point(.1f,.1f,0))
        assertEquals(tap,filter.finish(tap,1000f,1000f))
    }

    @Test fun finishClearsHeldStateForTheNextStrokeAndDelayedUpStillReviewsTail() {
        val filter=InkTailFilter();filter.begin(ReleaseSensitivity.STANDARD)
        filter.offer(point(0f,.8f,0));filter.offer(point(.01f,.01f,10))
        val input=samples(0f to .8f,.01f to .8f,.02f to .7f,.2f to .1f)
        assertEquals(input.take(3),filter.finish(input,1000f,1000f,500))
        assertEquals(1,filter.offer(point(.3f,.02f,510)).size)
    }
}
