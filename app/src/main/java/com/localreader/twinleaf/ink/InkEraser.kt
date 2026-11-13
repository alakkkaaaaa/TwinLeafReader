package com.localreader.twinleaf.ink

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One gesture clips ink against a round eraser swept along the pointer path. */
class InkEraseSession(
    private val original:List<InkStroke>,
    private val widthPx:Float,
    private val heightPx:Float,
    private val radiusPx:Float
) {
    init { require(widthPx>0 && heightPx>0 && radiusPx>0) }

    private data class Vec(val x:Double,val y:Double) {
        operator fun plus(other:Vec)=Vec(x+other.x,y+other.y)
        operator fun minus(other:Vec)=Vec(x-other.x,y-other.y)
        operator fun times(scale:Double)=Vec(x*scale,y*scale)
        fun dot(other:Vec)=x*other.x+y*other.y
    }
    private data class Interval(val start:Double,val end:Double)
    private val cellSize=96.0
    private val cells=HashMap<Long,MutableSet<Int>>()
    private val fragments=HashMap<Int,List<InkStroke>>()
    private var previous:Vec?=null

    init {
        original.forEachIndexed { index,stroke ->
            if(stroke.points.isEmpty()) return@forEachIndexed
            val pad=stroke.width*widthPx*.7f
            val left=stroke.points.minOf { it.x*widthPx }-pad
            val right=stroke.points.maxOf { it.x*widthPx }+pad
            val top=stroke.points.minOf { it.y*heightPx }-pad
            val bottom=stroke.points.maxOf { it.y*heightPx }+pad
            for(x in cell(left)..cell(right)) for(y in cell(top)..cell(bottom))
                cells.getOrPut(key(x,y)) { HashSet() }.add(index)
        }
    }

    /** Returns a new page snapshot only when ink changed. */
    fun eraseAt(x:Float,y:Float):List<InkStroke>? {
        val end=Vec(x.toDouble()*widthPx.toDouble(),y.toDouble()*heightPx.toDouble())
        val start=previous ?: end
        previous=end
        val candidates=HashSet<Int>()
        val pad=radiusPx.toDouble()
        for(cx in cell(min(start.x,end.x)-pad)..cell(max(start.x,end.x)+pad))
            for(cy in cell(min(start.y,end.y)-pad)..cell(max(start.y,end.y)+pad))
                cells[key(cx,cy)]?.let { candidates.addAll(it) }
        var changed=false
        for(index in candidates) {
            val before=fragments[index] ?: listOf(original[index])
            val after=before.flatMap { eraseStroke(it,start,end) }
            if(after!=before) { fragments[index]=after;changed=true }
        }
        return if(changed) snapshot() else null
    }

    fun snapshot():List<InkStroke> = original.indices.flatMap { fragments[it] ?: listOf(original[it]) }

    private fun eraseStroke(stroke:InkStroke,eraserStart:Vec,eraserEnd:Vec):List<InkStroke> {
        val pts=stroke.points
        if(pts.isEmpty()) return listOf(stroke)
        if(pts.size==1) {
            val r=radiusPx+pressureWidth(stroke,pts[0])*widthPx/2f
            return if(distanceToSegment(vector(pts[0]),eraserStart,eraserEnd)<=r.toDouble()) emptyList() else listOf(stroke)
        }
        val output=ArrayList<InkStroke>()
        val current=ArrayList<InkPoint>()
        var removed=false
        fun flush() {
            if(current.size>=2) output.add(stroke.copy(points=current.toList(),rawPoints=null,
                beautifyLevel=InkBeautifyLevel.OFF,autoStraightened=false))
            current.clear()
        }
        for(i in 1 until pts.size) {
            val a=pts[i-1];val b=pts[i]
            val radius=radiusPx.toDouble()+
                (max(pressureWidth(stroke,a),pressureWidth(stroke,b))*widthPx).toDouble()/2.0
            val inside=capsuleIntervals(vector(a),vector(b),eraserStart,eraserEnd,radius)
            if(inside.isEmpty()) {
                append(current,a);append(current,b)
                continue
            }
            removed=true
            var cursor=0.0
            for(interval in inside) {
                if(interval.start>cursor+1e-8) {
                    append(current,interpolate(a,b,cursor))
                    append(current,interpolate(a,b,interval.start))
                }
                flush()
                cursor=max(cursor,interval.end)
            }
            if(cursor<1.0-1e-8) {
                append(current,interpolate(a,b,cursor))
                append(current,b)
            }
        }
        flush()
        return if(removed) output else listOf(stroke)
    }

    private fun append(points:MutableList<InkPoint>,point:InkPoint) {
        val last=points.lastOrNull()
        if(last==null || last.x!=point.x || last.y!=point.y) points.add(point)
    }
    private fun vector(point:InkPoint)=Vec(point.x*widthPx.toDouble(),point.y*heightPx.toDouble())
    private fun cell(value:Number)=floor(value.toDouble()/cellSize).toInt()
    private fun key(x:Int,y:Int)=(x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

    private fun capsuleIntervals(a:Vec,b:Vec,e0:Vec,e1:Vec,r:Double):List<Interval> {
        val intervals=ArrayList<Interval>(3)
        circleInterval(a,b,e0,r)?.let { intervals.add(it) }
        circleInterval(a,b,e1,r)?.let { intervals.add(it) }
        val direction=e1-e0
        val lengthSquared=direction.dot(direction)
        if(lengthSquared>1e-12) {
            val stroke=b-a
            val projection=band((a-e0).dot(direction)/lengthSquared,stroke.dot(direction)/lengthSquared,0.0,1.0)
            val length=sqrt(lengthSquared)
            val cross0=(direction.x*(a.y-e0.y)-direction.y*(a.x-e0.x))/length
            val crossSlope=(direction.x*stroke.y-direction.y*stroke.x)/length
            val strip=band(cross0,crossSlope,-r,r)
            if(projection!=null && strip!=null) intersect(projection,strip)?.let { intervals.add(it) }
        }
        if(intervals.isEmpty()) return emptyList()
        val sorted=intervals.sortedBy { it.start }
        val merged=ArrayList<Interval>()
        for(interval in sorted) {
            val last=merged.lastOrNull()
            if(last!=null && interval.start<=last.end+1e-8)
                merged[merged.lastIndex]=Interval(last.start,max(last.end,interval.end))
            else merged.add(interval)
        }
        return merged.filter { it.end-it.start>1e-8 }
    }

    private fun circleInterval(a:Vec,b:Vec,center:Vec,r:Double):Interval? {
        val v=b-a;val w=a-center
        val qa=v.dot(v)
        if(qa<1e-12) return if(w.dot(w)<=r*r) Interval(0.0,1.0) else null
        val qb=2*w.dot(v);val qc=w.dot(w)-r*r
        val discriminant=qb*qb-4*qa*qc
        if(discriminant<0) return null
        val root=sqrt(discriminant)
        val low=max(0.0,(-qb-root)/(2*qa))
        val high=min(1.0,(-qb+root)/(2*qa))
        return if(low<=high) Interval(low,high) else null
    }

    private fun band(value:Double,slope:Double,low:Double,high:Double):Interval? {
        if(kotlin.math.abs(slope)<1e-12)
            return if(value in low..high) Interval(0.0,1.0) else null
        val a=(low-value)/slope;val b=(high-value)/slope
        val start=max(0.0,min(a,b));val end=min(1.0,max(a,b))
        return if(start<=end) Interval(start,end) else null
    }

    private fun intersect(a:Interval,b:Interval):Interval? {
        val start=max(a.start,b.start);val end=min(a.end,b.end)
        return if(start<=end) Interval(start,end) else null
    }

    private fun interpolate(a:InkPoint,b:InkPoint,t:Double):InkPoint {
        val f=t.toFloat()
        fun blend(first:Float,second:Float)=first+(second-first)*f
        return InkPoint(blend(a.x,b.x),blend(a.y,b.y),blend(a.pressure,b.pressure),
            blend(a.tilt,b.tilt),blend(a.orientation,b.orientation),
            a.timeMillis+((b.timeMillis-a.timeMillis)*t).toLong(),blend(a.rawPressure,b.rawPressure))
    }

    private fun distanceToSegment(point:Vec,a:Vec,b:Vec):Double {
        val v=b-a;val length=v.dot(v)
        val t=if(length<1e-12) 0.0 else ((point-a).dot(v)/length).coerceIn(0.0,1.0)
        return hypot(point.x-a.x-t*v.x,point.y-a.y-t*v.y)
    }
}
