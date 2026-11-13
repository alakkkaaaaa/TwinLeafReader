package com.localreader.twinleaf.ink

import org.junit.Assert.*
import org.junit.Test

class InkInputSessionTest {
    @Test fun reusedPointerCannotBridgeGesturesOrDevices() {
        val session = InkInputSession()
        session.begin(4, 0, 100, 100)
        assertTrue(session.owns(4, 0, 100))
        assertFalse(session.owns(5, 0, 100))
        assertFalse(session.owns(4, 0, 200))
        session.clear()
        assertFalse(session.acceptTime(150))
        session.begin(4, 0, 200, 200)
        assertFalse(session.acceptTime(150))
        assertTrue(session.acceptTime(200))
        assertFalse(session.acceptTime(200))
    }
    @Test fun secondaryPenDownRejectsEarlierHistoryButKeepsStationaryContact() {
        val session = InkInputSession()
        session.begin(2, 7, 50, 100)
        assertFalse(session.acceptTime(90))
        assertTrue(session.acceptTime(100))
        assertTrue(session.acceptTime(10000)) // no timeout: a held pen is legitimate.
    }
}
