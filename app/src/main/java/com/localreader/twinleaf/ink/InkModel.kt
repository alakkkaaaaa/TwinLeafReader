package com.localreader.twinleaf.ink

import kotlin.math.hypot

data class InkPoint(val x:Float,val y:Float,val pressure:Float=1f,
    val tilt:Float=0f,val orientation:Float=0f,val timeMillis:Long=0L,val rawPressure:Float=pressure)
enum class InkBrush { LEGACY, BALLPOINT, FOUNTAIN, PENCIL, HIGHLIGHTER, BRUSH }
enum class PressureCurve { SOFT, LINEAR, FIRM }
enum class ReleaseSensitivity { STEADY, STANDARD, LIGHT }
enum class InkBeautifyLevel { OFF, LIGHT, STANDARD, STRONG }
enum class StraightenSensitivity { STEADY, STANDARD, SENSITIVE }
enum class StylusButtonAction { ERASER, HIGHLIGHTER, PEN, NONE }
data class InkStroke(val points:List<InkPoint>,val color:Int,val width:Float,val highlight:Boolean=false,
    val brush:InkBrush=if(highlight) InkBrush.HIGHLIGHTER else InkBrush.LEGACY,
    val opacity:Float=if(highlight) 80f/255f else 1f,
    val curve:PressureCurve=PressureCurve.LINEAR,
    /** Original samples are retained when beautification changes the rendered points. */
    val rawPoints:List<InkPoint>?=null,
    val beautifyLevel:InkBeautifyLevel=InkBeautifyLevel.OFF,
    val autoStraightened:Boolean=false)
enum class InkTool { BROWSE, PEN, HIGHLIGHT, ERASER }

fun pressureWidth(stroke:InkStroke,point:InkPoint):Float {
    // Version 1 highlighters only carried the legacy `highlight` flag. Keep
    // their constant-width appearance even after the version 2 brush upgrade.
    if(stroke.highlight || stroke.brush==InkBrush.HIGHLIGHTER) return stroke.width
    val pressure=point.pressure.coerceIn(0f,1f)
    val response=when(stroke.curve) {
        PressureCurve.SOFT -> kotlin.math.sqrt(pressure)
        PressureCurve.LINEAR -> pressure
        PressureCurve.FIRM -> pressure*pressure
    }
    val multiplier=when(stroke.brush) {
        InkBrush.LEGACY -> .4f+.6f*response
        InkBrush.BALLPOINT,InkBrush.HIGHLIGHTER -> 1f
        InkBrush.FOUNTAIN -> .22f+.95f*response
        InkBrush.PENCIL -> .4f+.65f*response+.25f*(point.tilt/kotlin.math.PI.toFloat()).coerceIn(0f,1f)
        InkBrush.BRUSH -> .14f+1.25f*response
    }
    return stroke.width*multiplier
}

fun inkOpacity(stroke:InkStroke,point:InkPoint?=null):Float {
    val base=stroke.opacity.coerceIn(0f,1f)
    return if(stroke.brush==InkBrush.PENCIL && point!=null)
        (base*(.3f+.7f*point.pressure.coerceIn(0f,1f))).coerceIn(0f,1f)
    else base
}

/** Coordinates are relative to the displayed crop box, never to the screen or spread. */
fun pdfPoint(point:InkPoint,left:Float,bottom:Float,width:Float,height:Float,rotation:Int):Pair<Float,Float> =
    when(((rotation%360)+360)%360) {
        90 -> left+point.y*width to bottom+point.x*height
        180 -> left+(1-point.x)*width to bottom+point.y*height
        270 -> left+(1-point.y)*width to bottom+(1-point.x)*height
        else -> left+point.x*width to bottom+(1-point.y)*height
    }

fun hitsStroke(stroke:InkStroke,p:InkPoint,aspect:Float,tolerance:Float):Boolean {
    fun distance(a:InkPoint,b:InkPoint):Float {
        val dx=b.x-a.x; val dy=(b.y-a.y)/aspect
        val px=p.x-a.x; val py=(p.y-a.y)/aspect
        val t=if(dx*dx+dy*dy<1e-12f) 0f else ((px*dx+py*dy)/(dx*dx+dy*dy)).coerceIn(0f,1f)
        return hypot(px-t*dx,py-t*dy)
    }
    return if(stroke.points.size==1) distance(stroke.points[0],stroke.points[0])<tolerance+stroke.width/2
        else stroke.points.zipWithNext().any { distance(it.first,it.second)<tolerance+stroke.width/2 }
}
