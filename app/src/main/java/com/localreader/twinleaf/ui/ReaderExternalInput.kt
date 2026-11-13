package com.localreader.twinleaf.ui

import android.content.ContextWrapper
import android.graphics.RectF
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import com.localreader.twinleaf.MainActivity
import com.localreader.twinleaf.input.ReaderInputTarget
import com.localreader.twinleaf.input.ReaderNavigationKey

/** Attach once for the lifetime of a reading viewport, not once per page or focus change. */
@Composable
internal fun readerExternalInput(
    enabled: Boolean,
    paginated: Boolean,
    onTurn: (Int) -> Boolean = { false },
    onNavigate: (ReaderNavigationKey) -> Boolean = { onTurn(it.direction) },
    onScroll: (Float, Float, Long) -> Boolean = { _, _, _ -> false },
): Modifier {
    var context = LocalContext.current
    while (context is ContextWrapper && context !is MainActivity) context = context.baseContext
    val router = (context as? MainActivity)?.readerInput
    val active by rememberUpdatedState(enabled)
    val turn by rememberUpdatedState(onTurn)
    val navigate by rememberUpdatedState(onNavigate)
    val scroll by rememberUpdatedState(onScroll)
    val area = remember { RectF() }
    val target = remember(router, paginated) {
        ReaderInputTarget({ active }, { area.takeUnless { it.isEmpty } }, paginated,
            { navigate(it) }, { turn(it) }, { x, y, time -> scroll(x, y, time) })
    }
    DisposableEffect(router, target) {
        router?.attach(target)
        onDispose { router?.detach(target) }
    }
    LaunchedEffect(enabled) { if (!enabled) router?.reset() }
    return Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        area.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
}
