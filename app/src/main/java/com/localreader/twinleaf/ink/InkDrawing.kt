package com.localreader.twinleaf.ink

import android.graphics.*

fun drawInk(canvas:Canvas,rect:RectF,strokes:List<InkStroke>) {
    drawInkWithPainter(canvas,rect,strokes,InkStrokePainter())
}

internal fun drawInkWithPainter(canvas:Canvas,rect:RectF,strokes:List<InkStroke>,painter:InkStrokePainter) {
    canvas.save();canvas.clipRect(rect)
    for(stroke in strokes) painter.draw(canvas,stroke,rect)
    canvas.restore()
}

fun fitPage(left:Float,top:Float,width:Float,height:Float,aspect:Float):RectF {
    val w=minOf(width,height*aspect);val h=w/aspect
    return RectF(left+(width-w)/2,top+(height-h)/2,left+(width+w)/2,top+(height+h)/2)
}
