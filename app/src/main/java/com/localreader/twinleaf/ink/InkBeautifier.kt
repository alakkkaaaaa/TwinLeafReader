package com.localreader.twinleaf.ink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong

data class InkBeautifyBounds(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    init { require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && left<=right && top<=bottom) }
    fun clamp(point:InkPoint)=point.copy(x=point.x.coerceIn(left,right),y=point.y.coerceIn(top,bottom))
}

private data class StraightLineThresholds(
    val pathRatio:Float,
    val rmsFloor:Float,
    val rmsRatio:Float,
    val p95Floor:Float,
    val p95Ratio:Float,
    val backtrackRatio:Float,
)

/**
 * Non-destructive, deterministic post-processing for a completed stroke.
 * Geometry is measured in screen-like units via metricX/metricY while output remains in document coordinates.
 */
fun beautifyInkStroke(stroke:InkStroke,level:InkBeautifyLevel,autoStraighten:Boolean=true,
    metricX:Float=1f,metricY:Float=metricX,bounds:InkBeautifyBounds?=null,
    straightenSensitivity:StraightenSensitivity=StraightenSensitivity.STANDARD):InkStroke {
    if(stroke.points.isEmpty()) return stroke
    val sx=metricX.takeIf { it.isFinite() && it>0f } ?: 1f
    val sy=metricY.takeIf { it.isFinite() && it>0f } ?: sx
    val raw=stroke.rawPoints ?: stroke.points
    val safe=removeSpikes(stroke.points.map { point ->
        val normalized=point.copy(
            pressure=(point.pressure.takeIf(Float::isFinite) ?: 1f).coerceIn(0f,10f),
            rawPressure=(point.rawPressure.takeIf(Float::isFinite) ?: point.pressure).coerceIn(0f,10f),
            tilt=point.tilt.takeIf(Float::isFinite) ?: 0f,
            orientation=point.orientation.takeIf(Float::isFinite) ?: 0f)
        bounds?.clamp(normalized) ?: normalized
    },sx,sy)
    // Remove isolated sampling spikes, then resample at a small screen-space interval
    // before classifying. This makes the fit less dependent on stylus event density.
    val detectionPoints=if(safe.size>=3) resample(safe,3f,sx,sy) else safe
    val straighten=autoStraighten && looksLikeStraightLine(detectionPoints,sx,sy,level,straightenSensitivity)
    if(level==InkBeautifyLevel.OFF && !straighten) return stroke

    val spacing=when(level) {
        InkBeautifyLevel.OFF -> 2.2f
        InkBeautifyLevel.LIGHT -> 1.35f
        InkBeautifyLevel.STANDARD -> 1.75f
        InkBeautifyLevel.STRONG -> 2.2f
    }
    var output=resample(safe,spacing,sx,sy)
    if(straighten) output=straighten(output,bounds)
    else {
        val passes=when(level) {
            InkBeautifyLevel.OFF -> 0
            InkBeautifyLevel.LIGHT -> 1
            InkBeautifyLevel.STANDARD -> 2
            InkBeautifyLevel.STRONG -> 3
        }
        val strength=when(level) {
            InkBeautifyLevel.OFF -> 0f
            InkBeautifyLevel.LIGHT -> .18f
            InkBeautifyLevel.STANDARD -> .30f
            InkBeautifyLevel.STRONG -> .42f
        }
        repeat(passes) { output=smoothGeometry(output,strength,sx,sy,bounds) }
    }
    output=smoothPressure(output,when(level) {
        InkBeautifyLevel.OFF -> .12f
        InkBeautifyLevel.LIGHT -> .18f
        InkBeautifyLevel.STANDARD -> .28f
        InkBeautifyLevel.STRONG -> .38f
    })
    return stroke.copy(points=output,rawPoints=raw,beautifyLevel=level,autoStraightened=straighten)
}

internal fun looksLikeStraightLine(points:List<InkPoint>,metricX:Float=1f,metricY:Float=metricX,
    level:InkBeautifyLevel=InkBeautifyLevel.STANDARD,
    sensitivity:StraightenSensitivity=StraightenSensitivity.STANDARD):Boolean {
    if(points.size<3) return false
    val first=points.first();val last=points.last()
    val endpointX=(last.x-first.x)*metricX;val endpointY=(last.y-first.y)*metricY
    val chord=hypot(endpointX,endpointY)
    // Short marks and closed loops are more likely handwriting than intentional rules.
    val minimumLength=when(sensitivity) {
        StraightenSensitivity.STEADY -> 64f
        StraightenSensitivity.STANDARD -> 48f
        StraightenSensitivity.SENSITIVE -> 36f
    }
    if(chord<minimumLength) return false

    var path=0.0
    for(index in 1 until points.size)
        path+=distance(points[index-1],points[index],metricX,metricY).toDouble()
    if(path<=0.0) return false

    // Principal component / total least squares fit in metric (screen-like) space.
    val xs=points.map { it.x.toDouble()*metricX.toDouble() }
    val ys=points.map { it.y.toDouble()*metricY.toDouble() }
    val meanX=xs.average();val meanY=ys.average()
    var xx=0.0;var yy=0.0;var xy=0.0
    for(index in points.indices) {
        val x=xs[index]-meanX;val y=ys[index]-meanY
        xx+=x*x;yy+=y*y;xy+=x*y
    }
    var directionX=kotlin.math.cos(.5*kotlin.math.atan2(2.0*xy,xx-yy))
    var directionY=kotlin.math.sin(.5*kotlin.math.atan2(2.0*xy,xx-yy))
    if(directionX*endpointX.toDouble()+directionY*endpointY.toDouble()<0.0) {
        directionX=-directionX;directionY=-directionY
    }

    val perpendiculars=points.indices.map { index ->
        abs((xs[index]-meanX)*directionY-(ys[index]-meanY)*directionX)
    }.sorted()
    val squared=perpendiculars.sumOf { it*it }
    val rms=kotlin.math.sqrt(squared/perpendiculars.size)
    val p95=perpendiculars[((perpendiculars.size*.95).toInt()).coerceIn(0,perpendiculars.lastIndex)]

    // A fit alone can accept a loop or a path that doubles back. Require efficient,
    // mostly forward progression along its fitted axis as a separate shape check.
    var backwards=0.0
    var previous=0.0
    for(index in points.indices) {
        val projection=(xs[index]-meanX)*directionX+(ys[index]-meanY)*directionY
        if(index>0 && projection<previous) backwards+=previous-projection
        previous=projection
    }
    val base=when(level) {
        InkBeautifyLevel.OFF -> StraightLineThresholds(1.22f,4f,.040f,7f,.070f,.08f)
        InkBeautifyLevel.LIGHT -> StraightLineThresholds(1.25f,4.5f,.045f,8f,.080f,.09f)
        InkBeautifyLevel.STANDARD -> StraightLineThresholds(1.28f,5f,.050f,9f,.090f,.10f)
        InkBeautifyLevel.STRONG -> StraightLineThresholds(1.32f,5.5f,.055f,10f,.100f,.12f)
    }
    val thresholds=when(sensitivity) {
        StraightenSensitivity.STEADY -> base.copy(pathRatio=(base.pathRatio-.035f).coerceAtLeast(1.12f),
            rmsFloor=base.rmsFloor*.85f,rmsRatio=base.rmsRatio*.85f,
            p95Floor=base.p95Floor*.85f,p95Ratio=base.p95Ratio*.85f,backtrackRatio=base.backtrackRatio*.75f)
        StraightenSensitivity.STANDARD -> base
        StraightenSensitivity.SENSITIVE -> base.copy(pathRatio=(base.pathRatio+.050f).coerceAtMost(1.38f),
            rmsFloor=base.rmsFloor*1.22f,rmsRatio=base.rmsRatio*1.22f,
            p95Floor=base.p95Floor*1.22f,p95Ratio=base.p95Ratio*1.22f,backtrackRatio=base.backtrackRatio*1.25f)
    }
    val chordDouble=chord.toDouble()
    val rmsLimit=max(thresholds.rmsFloor.toDouble(),chordDouble*thresholds.rmsRatio)
    val p95Limit=max(thresholds.p95Floor.toDouble(),chordDouble*thresholds.p95Ratio)
    val maxBacktrack=max(8.0,chordDouble*thresholds.backtrackRatio)
    return path/chordDouble<=thresholds.pathRatio.toDouble() && rms<=rmsLimit && p95<=p95Limit && backwards<=maxBacktrack
}

private fun removeSpikes(points:List<InkPoint>,metricX:Float,metricY:Float):List<InkPoint> {
    if(points.size<3) return points.distinctAdjacent(metricX,metricY)
    val steps=points.zipWithNext().map { distance(it.first,it.second,metricX,metricY) }.filter { it>.05f }.sorted()
    val median=steps.getOrElse(steps.size/2) { 1f }.coerceAtLeast(.5f)
    val limit=max(18f,median*10f)
    val result=ArrayList<InkPoint>(points.size);result.add(points.first())
    for(index in 1 until points.lastIndex) {
        val previous=result.last();val current=points[index];val next=points[index+1]
        val incoming=distance(previous,current,metricX,metricY)
        val outgoing=distance(current,next,metricX,metricY)
        val bridge=distance(previous,next,metricX,metricY)
        if(!(incoming>limit && outgoing>limit && bridge<limit*.65f) && incoming>.05f) result.add(current)
    }
    if(distance(result.last(),points.last(),metricX,metricY)>.05f || result.size==1) result.add(points.last())
    return result
}

private fun List<InkPoint>.distinctAdjacent(metricX:Float,metricY:Float):List<InkPoint> {
    if(isEmpty()) return this
    val result=ArrayList<InkPoint>(size);result.add(first())
    for(index in 1 until size) if(distance(result.last(),this[index],metricX,metricY)>.05f) result.add(this[index])
    return result
}

private fun resample(points:List<InkPoint>,spacing:Float,metricX:Float,metricY:Float):List<InkPoint> {
    if(points.size<2) return points
    val cumulative=FloatArray(points.size)
    for(index in 1 until points.size) cumulative[index]=cumulative[index-1]+distance(points[index-1],points[index],metricX,metricY)
    val total=cumulative.last()
    if(total<=spacing) return listOf(points.first(),points.last()).distinct()
    // Never explode storage when Android supplied only a few sparse samples.
    val count=(total/spacing).toInt().coerceIn(1,minOf(25_000,points.size*2))
    val result=ArrayList<InkPoint>(count+1);result.add(points.first())
    var segment=1
    for(sample in 1 until count) {
        val target=total*sample/count
        while(segment<cumulative.lastIndex && cumulative[segment]<target) segment++
        val start=cumulative[segment-1];val end=cumulative[segment]
        val t=if(end-start<1e-5f) 0f else ((target-start)/(end-start)).coerceIn(0f,1f)
        result.add(interpolate(points[segment-1],points[segment],t))
    }
    result.add(points.last());return result
}

private fun smoothGeometry(points:List<InkPoint>,strength:Float,metricX:Float,metricY:Float,
    bounds:InkBeautifyBounds?):List<InkPoint> {
    if(points.size<3 || strength<=0f) return points
    return points.mapIndexed { index,current ->
        if(index==0 || index==points.lastIndex) current else {
            val before=points[index-1];val after=points[index+1]
            val ax=(current.x-before.x)*metricX;val ay=(current.y-before.y)*metricY
            val bx=(after.x-current.x)*metricX;val by=(after.y-current.y)*metricY
            val denominator=hypot(ax,ay)*hypot(bx,by)
            val cosine=if(denominator<1e-4f) 1f else (ax*bx+ay*by)/denominator
            // Preserve deliberate corners, hooks and sharp character turns.
            val localStrength=if(cosine<.72f) strength*.08f else strength
            val targetX=(before.x+current.x*2f+after.x)/4f
            val targetY=(before.y+current.y*2f+after.y)/4f
            val moved=current.copy(x=current.x+(targetX-current.x)*localStrength,
                y=current.y+(targetY-current.y)*localStrength)
            bounds?.clamp(moved) ?: moved
        }
    }
}

private fun smoothPressure(points:List<InkPoint>,strength:Float):List<InkPoint> {
    if(points.size<3 || strength<=0f) return points
    return points.mapIndexed { index,current ->
        if(index==0 || index==points.lastIndex) current else {
            val target=(points[index-1].pressure+current.pressure*2f+points[index+1].pressure)/4f
            current.copy(pressure=(current.pressure+(target-current.pressure)*strength).coerceIn(0f,10f))
        }
    }
}

private fun straighten(points:List<InkPoint>,bounds:InkBeautifyBounds?):List<InkPoint> {
    if(points.size<2) return points
    val first=bounds?.clamp(points.first()) ?: points.first()
    val last=bounds?.clamp(points.last()) ?: points.last()
    return points.mapIndexed { index,point ->
        val t=if(points.lastIndex==0) 0f else index.toFloat()/points.lastIndex
        val result=point.copy(x=first.x+(last.x-first.x)*t,y=first.y+(last.y-first.y)*t)
        bounds?.clamp(result) ?: result
    }
}

private fun interpolate(a:InkPoint,b:InkPoint,t:Float)=InkPoint(
    x=a.x+(b.x-a.x)*t,y=a.y+(b.y-a.y)*t,
    pressure=a.pressure+(b.pressure-a.pressure)*t,
    tilt=a.tilt+(b.tilt-a.tilt)*t,orientation=a.orientation+(b.orientation-a.orientation)*t,
    timeMillis=(a.timeMillis+(b.timeMillis-a.timeMillis)*t).roundToLong(),
    rawPressure=a.rawPressure+(b.rawPressure-a.rawPressure)*t)

private fun distance(a:InkPoint,b:InkPoint,metricX:Float,metricY:Float)=
    hypot((b.x-a.x)*metricX,(b.y-a.y)*metricY)
