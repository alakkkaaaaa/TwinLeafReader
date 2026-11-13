package com.localreader.twinleaf

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localreader.twinleaf.ink.InkStore
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import android.os.SystemClock

/** Run in a second instrumentation process after ReaderV2Test's handwriting scenario. */
@RunWith(AndroidJUnit4::class)
class InkPersistenceRestartTest {
    @Test fun savedStrokesLoadInANewProcess() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val uri=context.getSharedPreferences("v2_verification",0).getString("persistedUri",null)
        assumeNotNull(uri)
        lateinit var store:InkStore
        instrumentation.runOnMainSync { store=InkStore.get(context,uri!!) }
        val deadline=SystemClock.uptimeMillis()+10000
        while(!store.ready && store.failure==null && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(50)
        assertTrue(store.failure,store.ready)
        assertEquals(1,store.pages[0]?.size)
        assertEquals(.2f,store.pages[0]!!.first().points.first().x,.002f)
        assertTrue(store.visible)
    }
}
