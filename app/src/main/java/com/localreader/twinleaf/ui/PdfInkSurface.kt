package com.localreader.twinleaf.ui

import android.graphics.RectF
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.localreader.twinleaf.ink.*

@Composable
fun PdfInkOverlay(ink:InkStore,rects:Map<Int,RectF>,zoom:Float=1f,offset:Offset=Offset.Zero,
    onViewportChanged:(InkViewport)->Unit = {}) {
    // Snapshot reads here ensure native view gets invalidated for undo, load, hide and save.
    val revision=ink.revision;val ready=ink.ready;val visible=ink.visible;val tool=ink.tool
    AndroidView(modifier=Modifier.fillMaxSize(),factory={InkOverlayView(it)},update={
        it.store=ink;it.pageRects=rects
        it.zoom=zoom;it.panX=offset.x;it.panY=offset.y
        it.onViewportChanged=onViewportChanged
        @Suppress("UNUSED_VARIABLE") val changed=listOf(revision,ready,visible,tool)
        it.invalidate()
    })
}

@Composable
fun PdfZoomBox(tools:PdfToolsState,resetKey:Any,pageRects:Map<Int,RectF>?=null,content:@Composable ()->Unit) {
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(tools.zoom) { if(tools.zoom==1f) offset=Offset.Zero }
    val gesture=Modifier.pointerInput(resetKey) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed=false)
            var multiple=false
            var blocked=false
            do {
                val event=awaitPointerEvent(PointerEventPass.Initial)
                val pressed=event.changes.filter { it.pressed }
                if(pressed.size>2 || pressed.any { it.type!=PointerType.Touch }) blocked=true
                // Writing-mode input is owned by the native ink surface, including its
                // two-finger recognizer. Browse mode recognizes pinch even at 1x.
                if(!blocked && tools.ink.tool==InkTool.BROWSE && !tools.ink.drawing) {
                    if(pressed.size==2) multiple=true
                    if(pressed.size==2 || (pressed.size==1 && tools.zoom>1f && !multiple)) {
                        val previous=event.calculateCentroid(useCurrent=false)
                        val pan=event.calculatePan()
                        if(previous.x.isFinite() && previous.y.isFinite()) {
                            val next=transformInkViewport(InkViewport(tools.zoom,offset.x,offset.y),size.width.toFloat(),size.height.toFloat(),
                                previous.x,previous.y,previous.x+pan.x,previous.y+pan.y,event.calculateZoom())
                            tools.zoom=next.zoom;offset=Offset(next.panX,next.panY)
                        }
                        event.changes.forEach { it.consume() }
                    } else if(multiple) event.changes.forEach { it.consume() }
                }
            } while(event.changes.any { it.pressed })
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { size=it }.then(gesture)) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX=tools.zoom;scaleY=tools.zoom;translationX=offset.x;translationY=offset.y
        }) { content() }
        // Keep input outside Compose's scaled AndroidView interop boundary. This native layer
        // applies one explicit transform for both drawing and inverse pen-coordinate mapping.
        if(pageRects!=null) PdfInkOverlay(tools.ink,pageRects,tools.zoom,offset) {
            tools.zoom=it.zoom;offset=Offset(it.panX,it.panY)
        }
    }
}
