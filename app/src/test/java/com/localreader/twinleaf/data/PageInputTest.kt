package com.localreader.twinleaf.data

import org.junit.Assert.*
import org.junit.Test

class PageInputTest {
    @Test fun acceptsFirstAndLastPage() { assertEquals(1,validPage("1",8)); assertEquals(8,validPage("8",8)) }
    @Test fun rejectsZeroNegativeAndOutOfRange() {
        assertNull(validPage("0",8)); assertNull(validPage("-1",8)); assertNull(validPage("9",8))
    }
    @Test fun rejectsBlankAndOverflow() { assertNull(validPage("",8)); assertNull(validPage("999999999999999",8)) }
    @Test fun acceptsLeadingZeroes() { assertEquals(3,validPage("003",8)) }
}
