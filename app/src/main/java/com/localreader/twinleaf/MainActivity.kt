package com.localreader.twinleaf

import android.os.Bundle
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.localreader.twinleaf.input.ReaderInputRouter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.localreader.twinleaf.ui.TwinLeafApp
import com.localreader.twinleaf.ui.theme.TwinLeafTheme
import com.localreader.twinleaf.sync.CloudSyncScheduler

class MainActivity : ComponentActivity() {
    val readerInput by lazy { ReaderInputRouter(ViewConfiguration.get(this), resources.displayMetrics.density) }
    private var incomingBook by mutableStateOf<Uri?>(null)
    private var incomingConsumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CloudSyncScheduler.schedule(this)
        incomingConsumed = savedInstanceState?.getBoolean("incoming_consumed") ?: false
        incomingBook = if(incomingConsumed) null else intent?.data
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            TwinLeafTheme {
                TwinLeafApp(
                    incomingBook = incomingBook,
                    onIncomingBookConsumed = {
                        incomingBook = null
                        incomingConsumed = true
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingConsumed = false
        incomingBook = intent.data
    }

    // Activity/Window.Callback exposes this public interception point. AndroidX's internal
    // base class marks its implementation RestrictedApi; preserve its normal fallback chain.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (window.decorView.hasWindowFocus() && currentFocus?.onCheckIsTextEditor() != true && readerInput.key(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (window.decorView.hasWindowFocus() && readerInput.motion(event) { cancelReaderTouch(event) }) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (window.decorView.hasWindowFocus() && readerInput.touch(event) { cancelReaderTouch(event) }) return true
        return super.dispatchTouchEvent(event)
    }

    private fun cancelReaderTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) readerInput.reset()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("incoming_consumed",incomingConsumed)
        super.onSaveInstanceState(outState)
    }
}
