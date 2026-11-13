package com.localreader.twinleaf.data

import com.localreader.twinleaf.ui.theme.ThemeMode
import com.localreader.twinleaf.ui.theme.next
import org.junit.Assert.*
import org.junit.Test

class ThemeCycleTest {
    @Test fun cyclesThroughAllModesAndReturnsToSystem() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.SYSTEM.next())
        assertEquals(ThemeMode.DARK, ThemeMode.LIGHT.next())
        assertEquals(ThemeMode.SYSTEM, ThemeMode.DARK.next())
        assertEquals(ThemeMode.SYSTEM, ThemeMode.SYSTEM.next().next().next())
    }
}
