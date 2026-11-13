package com.localreader.twinleaf.ink

data class InkViewport(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)

/** Keep the document point under the fingers anchored while scaling, then clamp edges. */
fun transformInkViewport(old: InkViewport, width: Float, height: Float,
    previousX: Float, previousY: Float, focusX: Float, focusY: Float, scale: Float): InkViewport {
    if (!scale.isFinite() || scale <= 0f) return old
    val zoom=(old.zoom*scale).coerceIn(1f,3f)
    if(zoom==1f) return InkViewport()
    val ratio=zoom/old.zoom.coerceAtLeast(1f)
    val x=focusX-width/2f-(previousX-width/2f-old.panX)*ratio
    val y=focusY-height/2f-(previousY-height/2f-old.panY)*ratio
    val maxX=width*(zoom-1f)/2f;val maxY=height*(zoom-1f)/2f
    return InkViewport(zoom,x.coerceIn(-maxX,maxX),y.coerceIn(-maxY,maxY))
}
