package com.localreader.twinleaf.ink

import kotlin.math.hypot
import kotlin.math.max

/** Buffers only very low pressure samples. Recovery flushes them; a short low pressure tail is dropped. */
class InkTailFilter {
    private var sensitivity=ReleaseSensitivity.STANDARD
    private var peakPressure=0f
    private val pending=ArrayList<InkPoint>()

    fun begin(value:ReleaseSensitivity) {
        sensitivity=value
        peakPressure=0f
        pending.clear()
    }

    fun offer(point:InkPoint):List<InkPoint> {
        val raw=point.rawPressure.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        peakPressure=maxOf(peakPressure,raw)
        val (absolute,relative)=when(sensitivity) {
            ReleaseSensitivity.STEADY -> .08f to .30f
            ReleaseSensitivity.STANDARD -> .045f to .22f
            ReleaseSensitivity.LIGHT -> .018f to .12f
        }
        val low=peakPressure>=.06f && raw<max(absolute,peakPressure*relative)
        if(!low) return (pending.toList()+point).also { pending.clear() }
        pending.add(point)
        val maxPending=when(sensitivity) {
            ReleaseSensitivity.STEADY -> 6
            ReleaseSensitivity.STANDARD -> 4
            ReleaseSensitivity.LIGHT -> 2
        }
        val maxDuration=when(sensitivity) {
            ReleaseSensitivity.STEADY -> 65L
            ReleaseSensitivity.STANDARD -> 45L
            ReleaseSensitivity.LIGHT -> 25L
        }
        // Sustained light writing is real ink, even if its pressure is very low.
        return if(pending.size>maxPending || point.timeMillis-pending.first().timeMillis>maxDuration)
            pending.toList().also { pending.clear() }
        else emptyList()
    }

    fun acceptRelease(point:InkPoint,last:InkPoint?,widthPx:Float,heightPx:Float):Boolean {
        if(last==null) return false
        val distance=hypot((point.x-last.x)*widthPx,(point.y-last.y)*heightPx)
        val maxDistance=when(sensitivity) {
            ReleaseSensitivity.STEADY -> 12f
            ReleaseSensitivity.STANDARD -> 24f
            ReleaseSensitivity.LIGHT -> 40f
        }
        val elapsed=point.timeMillis-last.timeMillis
        if(distance<=1f || distance>maxDistance || elapsed<0 || elapsed>80) return false
        val raw=point.rawPressure
        if(!raw.isFinite()) return false
        val minimum=when(sensitivity) {
            ReleaseSensitivity.STEADY -> .08f
            ReleaseSensitivity.STANDARD -> .045f
            ReleaseSensitivity.LIGHT -> .018f
        }
        // A device that reports no meaningful pressure is judged by time and distance only.
        return peakPressure<.06f || raw>=max(minimum,peakPressure*.2f)
    }

    /**
     * Rechecks already accepted points at release. Coordinates may be normalized (pass page
     * dimensions) or world pixels (pass 1,1). No point is added by this operation.
     */
    fun trim(points:List<InkPoint>,widthPx:Float=1f,heightPx:Float=1f,
        releaseTimeMillis:Long=points.lastOrNull()?.timeMillis ?: 0L):List<InkPoint> {
        if(points.size<3 || !widthPx.isFinite() || !heightPx.isFinite() || widthPx<=0 || heightPx<=0) return points
        val window=when(sensitivity) {
            ReleaseSensitivity.STEADY -> 90L
            ReleaseSensitivity.STANDARD -> 75L
            ReleaseSensitivity.LIGHT -> 60L
        }
        // Use accepted sample time, rather than a delayed UP timestamp, to avoid losing the
        // review window when the driver batches or delays its final event.
        val cutoff=minOf(releaseTimeMillis,points.last().timeMillis)-window
        fun distance(a:InkPoint,b:InkPoint)=hypot((b.x-a.x)*widthPx,(b.y-a.y)*heightPx)
        fun speed(index:Int):Float = distance(points[index-1],points[index])/
            (points[index].timeMillis-points[index-1].timeMillis).coerceAtLeast(1L).toFloat()
        fun median(values:List<Float>):Float {
            if(values.isEmpty()) return 0f
            val sorted=values.sorted()
            val middle=sorted.size/2
            return if(sorted.size%2==0) (sorted[middle-1]+sorted[middle])/2f else sorted[middle]
        }
        val tailStart=(2 until points.size).firstOrNull { points[it].timeMillis>=cutoff } ?: return points
        val strokePressure=median(points.take(tailStart).map { it.rawPressure }
            .filter { it.isFinite() && it>=0f })
        for(index in tailStart until points.size) {
            val point=points[index];val previous=points[index-1];val before=points[index-2]
            val length=distance(previous,point)
            if(!length.isFinite()) return points.take(index)
            val historyStart=max(1,index-8)
            val referenceSpeed=median((historyStart until index).map { speed(it) }.filter { it.isFinite() })
            val currentSpeed=speed(index)
            val dt=(point.timeMillis-previous.timeMillis).coerceAtLeast(1L).toFloat()
            val vx=(previous.x-before.x)*widthPx;val vy=(previous.y-before.y)*heightPx
            val wx=(point.x-previous.x)*widthPx;val wy=(point.y-previous.y)*heightPx
            val priorLength=hypot(vx,vy)
            val cosine=if(priorLength>1f && length>1f) (vx*wx+vy*wy)/(priorLength*length) else 1f
            val turn=cosine<.15f && length>6f
            val backJump=cosine<-.35f && distance(before,point)<priorLength*.65f
            // The pressure baseline excludes the suspect point. Median tolerates a pressure
            // spike and lets an entirely light-written stroke establish its own low baseline.
            val referencePressure=max(strokePressure*.75f,median(points.subList(max(0,index-12),index)
                .map { it.rawPressure }.filter { it.isFinite() && it>=0f }))
            val (absolute,relative)=when(sensitivity) {
                ReleaseSensitivity.STEADY -> .08f to .30f
                ReleaseSensitivity.STANDARD -> .045f to .22f
                ReleaseSensitivity.LIGHT -> .018f to .12f
            }
            val raw=point.rawPressure
            val lowPressure=referencePressure>=.06f && raw.isFinite() &&
                raw<max(absolute,referencePressure*relative)
            val collapse=raw.isFinite() && previous.rawPressure.isFinite() && raw<previous.rawPressure*.55f
            val factor=when(sensitivity) {
                ReleaseSensitivity.STEADY -> 2.8f
                ReleaseSensitivity.STANDARD -> 3.5f
                ReleaseSensitivity.LIGHT -> 4.5f
            }
            val speedSpike=currentSpeed>max(.9f,referenceSpeed*factor) &&
                length>max(8f,referenceSpeed*dt*2.5f)
            val hardJump=length>max(28f,referenceSpeed*dt*6f) && currentSpeed>2.2f
            val extremeSpike=currentSpeed>max(5f,referenceSpeed*8f) && length>60f
            // A smooth pressure taper plus a faster same-direction flick is kept. Removing
            // normal/light/pressureless ink requires corroborating geometry, not pressure alone.
            if((lowPressure && (turn || backJump || (speedSpike && collapse))) ||
                (hardJump && (turn || extremeSpike))) return points.take(index)
        }
        return points
    }

    /** Commits the trimmed accepted list and clears any held low-pressure tail for the next DOWN. */
    fun finish(points:List<InkPoint>,widthPx:Float=1f,heightPx:Float=1f,
        releaseTimeMillis:Long=points.lastOrNull()?.timeMillis ?: 0L):List<InkPoint> {
        val result=trim(points,widthPx,heightPx,releaseTimeMillis)
        discard()
        return result
    }

    fun discard() { pending.clear();peakPressure=0f }
}
