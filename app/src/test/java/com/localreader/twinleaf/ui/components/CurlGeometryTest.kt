package com.localreader.twinleaf.ui.components

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CurlGeometryTest {
    private fun curve(p:Float,y:Float=.9f,dy:Float=0f)=CurlGeometry().apply { configure(1695f,2256f,p,y,dy) }

    @Test fun endpointsMatchFlatFrontAndReversedBackWithoutScaling() {
        val out=FloatArray(2)
        for(x in listOf(0f,500f,1695f)) for(y in listOf(0f,400f,2256f)) {
            curve(0f).writePoint(x,y,out,0)
            assertEquals(x,out[0],.01f);assertEquals(y,out[1],.01f)
            curve(1f).writePoint(x,y,out,0)
            assertEquals(-x,out[0],.01f);assertEquals(y,out[1],.01f)
        }
    }
    @Test fun stationaryRegionIsExactlyStationary() {
        val c=curve(.25f);val out=FloatArray(2)
        c.writePoint(0f,0f,out,0)
        assertEquals(0f,out[0],0f);assertEquals(0f,out[1],0f)
    }
    @Test fun projectionIsContinuousAtBothJoinsAndHasRealCurvature() {
        val c=curve(.5f)
        for(d in listOf(0f,PI.toFloat()*c.radius)) {
            assertEquals(c.projectedDistance(d-.001f),c.projectedDistance(d+.001f),.01f)
        }
        assertEquals(c.radius,c.projectedDistance(c.tangent),.01f)
        assertEquals(2*c.radius,c.heightAt(PI.toFloat()*c.radius),.01f)
        assertTrue(c.projectedDistance(c.tangent/2f) > c.projectedDistance(c.tangent)/2f)
    }
    @Test fun currentVerticalDragChangesFoldDirection() {
        assertTrue(curve(.4f,.8f,-.2f).ny > curve(.4f,.8f,.2f).ny)
        assertTrue(curve(.4f,.1f).ny < 0f)
        assertEquals(0f,curve(.4f,.5f).ny,0f)
    }
    @Test fun meshesShareExactSilhouetteAndStayInsideTheirTextureHalf() {
        val front=CurlMesh();val back=CurlMesh()
        for(p in listOf(.01f,.2f,.5f,.8f,.99f)) for(y in listOf(.02f,.5f,.98f)) {
            val c=curve(p,y)
            front.update(c,false,3390,2256,true,false)
            back.update(c,true,3390,2256,false,true)
            for(row in 0..front.rows) {
                val f=(row*(front.columns+1)+front.columns)*2
                val b=row*(back.columns+1)*2
                assertEquals(front.positions[f],back.positions[b],.001f)
                assertEquals(front.positions[f+1],back.positions[b+1],.001f)
            }
            for(i in front.positions.indices step 2) {
                assertTrue(front.positions[i].isFinite() && back.positions[i+1].isFinite())
                assertTrue(front.texture[i] in 1695.5f..3389.5f)
                assertTrue(back.texture[i] in .5f..1694.5f)
            }
        }
    }
    @Test fun backTextureIsUprightAfterLandingAndIndicesAreValid() {
        val mesh=CurlMesh()
        mesh.update(curve(1f),true,3390,2256,false,true)
        assertEquals(0f,mesh.positions[0],.01f)
        assertEquals(1694.5f,mesh.texture[0],.01f)
        val edge=mesh.columns*2
        assertEquals(-1695f,mesh.positions[edge],.01f)
        assertEquals(.5f,mesh.texture[edge],.01f)
        assertTrue(mesh.indices.all { it.toInt() in mesh.colors.indices })
    }
    @Test fun buffersAreReusedAndExtremeDragsStayFinite() {
        val c=CurlGeometry();val mesh=CurlMesh()
        val positions=mesh.positions;val texture=mesh.texture
        for(p in 0..100) for(y in listOf(0f,.5f,1f)) for(dy in listOf(-2f,0f,2f)) {
            c.configure(1695f,2256f,p/100f,y,dy)
            mesh.update(c,true,3390,2256,true,false)
            assertTrue(mesh.positions.all { it.isFinite() })
            assertTrue(c.splitX(0f) in 0f..1695f)
        }
        assertSame(positions,mesh.positions);assertSame(texture,mesh.texture)
    }
}
