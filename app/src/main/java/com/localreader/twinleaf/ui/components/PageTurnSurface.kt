package com.localreader.twinleaf.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun PageTurnSurface(
    current: Bitmap?, previous: Bitmap?, next: Bitmap?, night: Boolean,
    onTurn: (Int) -> Unit, onSpine: () -> Unit,
    interactive: Boolean = true,
    onView: (PageTurnTarget) -> Unit = {},
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { CurlBookView(it) },
        update = {
            it.current = current; it.previous = previous; it.next = next
            it.night = night; it.onTurn = onTurn; it.onSpine = onSpine
            it.isEnabled=interactive
            onView(it)
            it.invalidate()
        },
    )
}
