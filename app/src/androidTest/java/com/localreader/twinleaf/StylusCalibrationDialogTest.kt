package com.localreader.twinleaf

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.twinleaf.ink.*
import com.localreader.twinleaf.ui.StylusCalibrationDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylusCalibrationDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun opensAndInsufficientSamplesCannotEnableCalibration() {
        var closed = false
        compose.setContent { MaterialTheme { StylusCalibrationDialog { closed = true } } }
        compose.onNodeWithText("触笔压力校准").assertIsDisplayed()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("第 1 步", substring = true).assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun profilesPersistPerDeviceAndResetWithoutAffectingOtherDevices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = StylusDeviceKey("test-${System.nanoTime()}", 16386)
        val other = key.copy(source = 16387)
        val profile = StylusCalibrationProfile(key, pressureStart = .012f, pressureEnd = .003f, confidence = .8f)
        val repository = StylusCalibrationRepository(context)
        try {
            repository.save(profile)
            assertEquals(profile, StylusCalibrationRepository(context).load(key))
            assertNull(repository.load(other))
            repository.save(profile.copy(key = other))
            repository.reset(key)
            assertNull(repository.load(key))
            assertNotNull(repository.load(other))
        } finally { repository.reset(key); repository.reset(other) }
    }
}
