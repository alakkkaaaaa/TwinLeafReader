package com.localreader.twinleaf

import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.twinleaf.ui.components.CurlBookView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurlRendererTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun frame(left:Int,right:Int)=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888).apply {
        val c=Canvas(this);val p=Paint()
        p.color=left;c.drawRect(0f,0f,600f,800f,p)
        p.color=right;c.drawRect(600f,0f,1200f,800f,p)
    }
    private fun send(v:CurlBookView,action:Int,x:Float,y:Float) {
        val t=SystemClock.uptimeMillis()
        MotionEvent.obtain(t,t,action,x*v.width,y*v.height,0).also { v.dispatchTouchEvent(it);it.recycle() }
    }
    private fun image(v:CurlBookView)=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).apply { v.draw(Canvas(this)) }
    private fun waitIdle(v:CurlBookView) {
        repeat(40) {
            var idle=false;instrumentation.runOnMainSync { idle=!v.isTurning }
            if(idle) return
            SystemClock.sleep(50)
        }
        fail("Animation did not settle")
    }
    @Test fun bothDirectionsLandOnCorrectTextureHalvesAndCommitOnce() {
        for(dir in listOf(1,-1)) {
            lateinit var v:CurlBookView;var commits=0
            val source=frame(Color.RED,Color.BLUE);val destination=frame(Color.GREEN,Color.YELLOW)
            instrumentation.runOnMainSync {
                v=CurlBookView(context);v.layout(0,0,1200,800)
                v.current=source;if(dir>0) v.next=destination else v.previous=destination
                v.onTurn={assertEquals(dir,it);commits++}
                val start=if(dir>0) .98f else .02f
                send(v,MotionEvent.ACTION_DOWN,start,.5f)
                send(v,MotionEvent.ACTION_MOVE,start-dir*.65f*.98f,.5f)
                val bitmap=image(v)
                if(dir>0) {
                    assertEquals(Color.GREEN,bitmap.getPixel(100,400))
                    assertEquals(Color.YELLOW,bitmap.getPixel(1100,400))
                } else {
                    assertEquals(Color.GREEN,bitmap.getPixel(100,400))
                    assertEquals(Color.YELLOW,bitmap.getPixel(1100,400))
                }
                bitmap.recycle()
                send(v,MotionEvent.ACTION_UP,start-dir*.65f*.98f,.5f)
            }
            waitIdle(v)
            instrumentation.runOnMainSync { assertSame(destination,v.current);assertEquals(1,commits) }
        }
    }
    @Test fun canceledAndInterruptedTurnsNeverCommitStalePages() {
        lateinit var v:CurlBookView;var commits=0
        val source=frame(Color.RED,Color.BLUE);val target=frame(Color.GREEN,Color.YELLOW)
        instrumentation.runOnMainSync {
            v=CurlBookView(context);v.layout(0,0,1200,800);v.current=source;v.next=target;v.onTurn={commits++}
            send(v,MotionEvent.ACTION_DOWN,.95f,.9f);send(v,MotionEvent.ACTION_MOVE,.6f,.8f)
            send(v,MotionEvent.ACTION_CANCEL,.6f,.8f)
        }
        waitIdle(v)
        instrumentation.runOnMainSync {
            assertSame(source,v.current);assertEquals(0,commits)
            send(v,MotionEvent.ACTION_DOWN,.95f,.9f);send(v,MotionEvent.ACTION_UP,.95f,.9f)
            v.current=frame(Color.CYAN,Color.MAGENTA)
        }
        SystemClock.sleep(600)
        instrumentation.runOnMainSync {
            assertEquals(0,commits);assertFalse(v.isTurning)
            // Resizing and entering writing/zoom modes also cancel the old animation.
            send(v,MotionEvent.ACTION_DOWN,.95f,.9f);send(v,MotionEvent.ACTION_UP,.95f,.9f)
            v.isEnabled=false
        }
        SystemClock.sleep(600)
        instrumentation.runOnMainSync { assertEquals(0,commits);assertFalse(v.isTurning) }
    }
    @Test fun hardwareCanvasActuallyDrawsTheTurningLeaf() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var v:CurlBookView
            scenario.onActivity { activity ->
                v=CurlBookView(activity);v.current=frame(Color.RED,Color.BLUE);v.next=frame(Color.GREEN,Color.YELLOW)
                activity.setContentView(v)
            }
            SystemClock.sleep(600)
            val position=IntArray(2)
            scenario.onActivity {
                assertTrue(v.isHardwareAccelerated)
                send(v,MotionEvent.ACTION_DOWN,.98f,.5f);send(v,MotionEvent.ACTION_MOVE,.72f,.5f)
                v.getLocationOnScreen(position)
            }
            SystemClock.sleep(300)
            val screenshot=instrumentation.uiAutomation.takeScreenshot()
            // p=.4: left stays red, the returning reverse leaf occupies the middle-right.
            val sample=screenshot.getPixel(position[0]+(v.width*.6f).toInt(),position[1]+v.height/2)
            assertTrue("Reverse page green texture must be visible on accelerated Canvas: $sample",Color.green(sample)>Color.red(sample)+40 && Color.green(sample)>Color.blue(sample)+40)
            screenshot.recycle()
            scenario.onActivity { send(v,MotionEvent.ACTION_CANCEL,.72f,.5f) }
        }
    }
}
