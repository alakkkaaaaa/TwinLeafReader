package com.localreader.twinleaf.input

import org.junit.Assert.*
import org.junit.Test

class ScrollPageGateTest {
    @Test fun repeatedScrollGesturesNeverNeedAClickOrDownEvent() {
        val gate=ScrollPageGate(48f)
        val turns=mutableListOf<Int>()
        repeat(20) { gesture ->
            repeat(4) { event -> gate.scroll(0f,12f,gesture*500L+event*10) { turns.add(it);true } }
        }
        assertEquals(List(20) {1},turns)
    }

    @Test fun continuousScrollIsRateLimitedAndDoesNotBuildBacklog() {
        val gate=ScrollPageGate(48f)
        val times=mutableListOf<Long>()
        for(time in 0L..1000L step 10) gate.scroll(0f,500f,time) {times.add(time);true}
        assertTrue(times.size in 4..5)
        assertTrue(times.zipWithNext().all { (a,b)->b-a>=220 })
        gate.scroll(0f,1f,1500) {fail("Old momentum must not be queued");false}
    }

    @Test fun reversedDirectionAndAxisDoNotInheritOldTravel() {
        val gate=ScrollPageGate(48f)
        val turns=mutableListOf<Int>()
        fun scroll(x:Float,y:Float,time:Long)=gate.scroll(x,y,time) {turns.add(it);true}
        scroll(0f,40f,0);scroll(0f,-20f,10)
        assertTrue(turns.isEmpty())
        scroll(0f,-30f,20)
        assertEquals(listOf(-1),turns)
        scroll(0f,40f,500);scroll(20f,0f,510)
        assertEquals(1,turns.size)
        scroll(30f,1f,520)
        assertEquals(listOf(-1,1),turns)
    }

    @Test fun busyTargetDropsRequestRatherThanReplayingItLater() {
        val gate=ScrollPageGate(48f)
        var busy=true;var attempts=0;var committed=0
        val turn:(Int)->Boolean={ attempts++;if(busy) false else {committed++;true} }
        gate.scroll(0f,5000f,0,turn)
        busy=false
        gate.scroll(0f,1f,500,turn)
        assertEquals(1,attempts);assertEquals(0,committed)
        gate.scroll(0f,48f,510,turn)
        assertEquals(2,attempts);assertEquals(1,committed)
    }

    @Test fun invalidZeroAndJitterNeverTurnAndResetDropsPartialGesture() {
        val gate=ScrollPageGate(48f)
        var turns=0
        val turn:(Int)->Boolean={turns++;true}
        gate.scroll(Float.NaN,100f,0,turn);gate.scroll(0f,Float.POSITIVE_INFINITY,1,turn)
        gate.scroll(0f,0f,2,turn);gate.scroll(0f,40f,3,turn)
        gate.reset();gate.scroll(0f,10f,4,turn)
        assertEquals(0,turns)
        gate.scroll(0f,40f,2000,turn)
        assertEquals(0,turns)
        gate.scroll(0f,10f,2010,turn)
        assertEquals(1,turns)
    }
}
