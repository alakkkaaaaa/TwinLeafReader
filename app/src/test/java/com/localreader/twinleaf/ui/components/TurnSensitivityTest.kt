package com.localreader.twinleaf.ui.components

import org.junit.Assert.*
import org.junit.Test

class TurnSensitivityTest {
    @Test fun shortTabletDragCommits() = assertTrue(TurnSensitivity.shouldCommit(150f,3390,2f,0f))
    @Test fun smallSlowMovementCancels() = assertFalse(TurnSensitivity.shouldCommit(30f,3390,2f,0f))
    @Test fun shortFastFlickCommits() = assertTrue(TurnSensitivity.shouldCommit(35f,3390,2f,1500f))
    @Test fun jitterDoesNotCommitEvenAtHighSpeed() = assertFalse(TurnSensitivity.shouldCommit(8f,3390,2f,3000f))
    @Test fun flickBackCancels() = assertFalse(TurnSensitivity.shouldCommit(400f,3390,2f,-1000f))
    @Test fun dragProgressHasIncreasedGainAndStaysBounded() {
        assertTrue(TurnSensitivity.progress(339f,3390) > 0.15f)
        assertEquals(0f,TurnSensitivity.progress(-1f,3390),0f)
        assertEquals(.98f,TurnSensitivity.progress(4000f,3390),0f)
    }
}
