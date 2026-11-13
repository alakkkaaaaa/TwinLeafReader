package com.localreader.twinleaf.ui.components

import kotlin.math.*

/** One right-hand leaf, projected across an oblique fold line. Points on the
 * stationary side never move; a half cylinder joins the reversed flat tail.
 * This is an app-side implementation of the mathematical model, not a JNI port.
 */
internal class CurlGeometry {
    var width=1f; private set
    var height=1f; private set
    var radius=.001f; private set
    var nx=1f; private set
    var ny=0f; private set
    var fold=1f; private set
    val tangent:Float get()=PI.toFloat()*radius/2f
    val angleDegrees:Float get()=atan2(ny,nx)*180f/PI.toFloat()

    fun configure(w:Float,h:Float,progress:Float,touchY:Float,verticalTravel:Float=0f) {
        width=w.coerceAtLeast(1f);height=h.coerceAtLeast(1f)
        val p=progress.coerceIn(0f,1f)
        val lift=if(p==0f || p==1f) 0f else sin(PI.toFloat()*p)
        val slope=((touchY-.5f)*.65f-verticalTravel*.8f).coerceIn(-.5f,.5f)*lift
        nx=1f/sqrt(1f+slope*slope);ny=slope*nx
        radius=(width*.115f*lift).coerceAtLeast(.001f)
        fold=(width*nx+max(0f,height*ny))*(1f-p)-PI.toFloat()*radius/2f
        if(p==0f) fold=width
    }
    fun distance(x:Float,y:Float)=x*nx+y*ny-fold
    fun splitX(y:Float)=((fold+tangent-y*ny)/nx).coerceIn(0f,width)

    /** Continuous at both flat/cylinder and cylinder/reversed-tail joins. */
    fun projectedDistance(d:Float):Float=when {
        d<=0f -> d
        d<=PI.toFloat()*radius -> radius*sin(d/radius)
        else -> PI.toFloat()*radius-d
    }
    fun heightAt(d:Float):Float=if(d<=0f) 0f else radius*(1f-cos((d/radius).coerceAtMost(PI.toFloat())))

    fun writePoint(x:Float,y:Float,vertices:FloatArray,offset:Int):Int {
        val d=distance(x,y)
        val shift=projectedDistance(d)-d
        vertices[offset]=x+nx*shift;vertices[offset+1]=y+ny*shift
        val theta=(d/radius).coerceIn(0f,PI.toFloat())
        val c=((.70f+.30f*abs(cos(theta)))*255f).roundToInt().coerceIn(0,255)
        return (0xff000000L or (c.toLong() shl 16) or (c.toLong() shl 8) or c.toLong()).toInt()
    }
}

/** Clip each face in material coordinates before tessellation. Explicit texture
 * coordinates keep letters on the correct material point at the silhouette.
 * Arrays and indices are allocated once, not during drawing or page turns.
 */
internal class CurlMesh(val columns:Int=64,val rows:Int=48) {
    val positions=FloatArray((columns+1)*(rows+1)*2)
    val texture=FloatArray(positions.size)
    val colors=IntArray(positions.size/2)
    val indices=ShortArray(columns*rows*6)
    init {
        var i=0
        for(row in 0 until rows) for(col in 0 until columns) {
            val a=row*(columns+1)+col;val b=a+columns+1
            for(v in intArrayOf(a,b,a+1,a+1,b,b+1)) indices[i++]=v.toShort()
        }
    }
    fun update(curve:CurlGeometry,back:Boolean,textureWidth:Int,textureHeight:Int,right:Boolean,mirror:Boolean) {
        var vertex=0
        val half=textureWidth/2f
        for(row in 0..rows) {
            val y=curve.height*row/rows
            val split=curve.splitX(y)
            val start=if(back) split else 0f
            val end=if(back) curve.width else split
            for(col in 0..columns) {
                val x=start+(end-start)*col/columns
                colors[vertex]=curve.writePoint(x,y,positions,vertex*2)
                val u=(x/curve.width).coerceIn(0f,1f)
                // Half-pixel inset avoids sampling the adjacent page across the spine.
                texture[vertex*2]=(if(right) half else 0f)+.5f+(half-1f)*(if(mirror) 1f-u else u)
                texture[vertex*2+1]=.5f+(textureHeight-1f)*row/rows
                vertex++
            }
        }
    }
}
