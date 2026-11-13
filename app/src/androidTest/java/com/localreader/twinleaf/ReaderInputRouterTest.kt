package com.localreader.twinleaf

import android.graphics.RectF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.twinleaf.input.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderInputRouterTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun router()=ReaderInputRouter(ViewConfiguration.get(context),1f)
    private fun motion(x:Float=0f,y:Float=-1f,time:Long=1000,device:Int=5,source:Int=InputDevice.SOURCE_MOUSE,
                       tool:Int=MotionEvent.TOOL_TYPE_MOUSE,meta:Int=0,screenX:Float=200f):MotionEvent {
        val properties=arrayOf(MotionEvent.PointerProperties().apply {id=0;toolType=tool})
        val coords=arrayOf(MotionEvent.PointerCoords().apply {
            this.x=screenX;this.y=200f
            setAxisValue(MotionEvent.AXIS_HSCROLL,x);setAxisValue(MotionEvent.AXIS_VSCROLL,y)
        })
        return MotionEvent.obtain(0,time,MotionEvent.ACTION_SCROLL,1,properties,coords,meta,0,1f,1f,device,0,source,0)
    }
    private fun scroll(router:ReaderInputRouter,event:MotionEvent):Boolean=try {router.motion(event)} finally {event.recycle()}
    private fun target(enabled:()->Boolean={true},paged:Boolean=true,turn:(Int)->Boolean={true},
                       navigate:(ReaderNavigationKey)->Boolean={true},wheel:(Float,Float,Long)->Boolean={_,_,_->true})=
        ReaderInputTarget(enabled,{RectF(0f,100f,1000f,900f)},paged,navigate,turn,wheel)

    @Test fun scrollWorksRepeatedlyWithoutFocusClickOrPointerDown() {
        val router=router();val turns=mutableListOf<Int>()
        router.attach(target(turn={turns.add(it);true}))
        repeat(12) { assertTrue(scroll(router,motion(y=-4f,time=1000+it*500L))) }
        assertTrue(scroll(router,motion(x=-4f,y=0f,time=8000,source=InputDevice.SOURCE_TOUCHPAD,screenX=0f)))
        assertEquals(List(12) {1}+(-1),turns)
    }

    @Test fun controlsOutsideViewportModifiersAndStylusAreNotHijacked() {
        val router=router();var active=true;var count=0
        router.attach(target(enabled={active},turn={count++;true}))
        assertFalse(scroll(router,motion(screenX=1200f)))
        assertFalse(scroll(router,motion(meta=KeyEvent.META_CTRL_ON)))
        assertFalse(scroll(router,motion(source=InputDevice.SOURCE_STYLUS,tool=MotionEvent.TOOL_TYPE_STYLUS)))
        assertFalse(scroll(router,motion(source=InputDevice.SOURCE_MOUSE,y=0f)))
        active=false
        assertFalse(scroll(router,motion(y=-4f)))
        assertEquals(0,count)
    }

    @Test fun directionsHaveOneDownUpPairAndRepeatsAreBounded() {
        val router=router();val commands=mutableListOf<ReaderNavigationKey>()
        router.attach(target(navigate={commands.add(it);true}))
        val codes=listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_PAGE_UP,KeyEvent.KEYCODE_PAGE_DOWN)
        codes.forEachIndexed { i,code ->
            val t=1000L+i*500
            assertTrue(router.key(KeyEvent(t,t,KeyEvent.ACTION_DOWN,code,0)))
            assertTrue(router.key(KeyEvent(t,t+10,KeyEvent.ACTION_DOWN,code,1)))
            assertTrue(router.key(KeyEvent(t,t+20,KeyEvent.ACTION_UP,code,0)))
            assertFalse(router.key(KeyEvent(t,t+20,KeyEvent.ACTION_UP,code,0)))
        }
        assertEquals(ReaderNavigationKey.entries,commands)
        assertFalse(router.key(KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,0,KeyEvent.META_CTRL_ON)))
        assertFalse(router.key(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER)))
    }

    @Test fun scrollModeReceivesContinuousDeltasAndDisposalCannotLeaveOldTargetActive() {
        val router=router();var count=0;var lastY=0f
        val old=target(paged=false,wheel={_,y,_->count++;lastY=y;true})
        val replacement=target(paged=false,wheel={_,y,_->count++;lastY=y;true})
        router.attach(old);router.attach(replacement);router.detach(old)
        repeat(8) {assertTrue(scroll(router,motion(y=-.25f,time=1000+it*10L)))}
        assertEquals(8,count);assertTrue(lastY>0f)
        router.detach(replacement)
        assertFalse(scroll(router,motion(y=-10f,time=3000)))
    }

    @Test @SdkSuppress(minSdkVersion=34)
    fun modernTouchpadUsesDisplayPixelDeltasAndIncludesHistoricalSamples() {
        val router=router();var pixels=0f
        router.attach(target(paged=false,wheel={_,y,_->pixels+=y;true}))
        val properties=arrayOf(MotionEvent.PointerProperties().apply {id=0;toolType=MotionEvent.TOOL_TYPE_FINGER})
        fun coords(y:Float)=arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE,y)
        })
        val event=MotionEvent.obtain(1000,1000,MotionEvent.ACTION_MOVE,1,properties,coords(12f),0,0,1f,1f,
            7,0,InputDevice.SOURCE_TOUCHPAD,0)
        event.addBatch(1010,coords(14f),0)
        assertTrue(scroll(router,event))
        assertEquals(26f,pixels,.001f)
        val up=MotionEvent.obtain(1000,1015,MotionEvent.ACTION_UP,1,properties,coords(0f),
            0,0,1f,1f,7,0,InputDevice.SOURCE_TOUCHPAD,0)
        assertTrue(scroll(router,up))
        // An unclassified pinch-only event must be left to the platform, not turned into ink/page input.
        val pinch=MotionEvent.obtain(1020,1020,MotionEvent.ACTION_MOVE,1,properties,arrayOf(
            MotionEvent.PointerCoords().apply {setAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR,1.1f)}),
            0,0,1f,1f,7,0,InputDevice.SOURCE_TOUCHPAD,0)
        assertFalse(scroll(router,pinch))
        assertEquals(26f,pixels,.001f)
    }

    @Test @SdkSuppress(minSdkVersion=34)
    fun mouseClassifiedSwipesRepeatAndUnclassifiedOutsideUpReleasesOwnership() {
        val router=router();val turns=mutableListOf<Int>();var cancels=0
        router.attach(target(turn={turns.add(it);true}))
        fun send(e:MotionEvent)=try {router.touch(e) {cancels++}} finally {e.recycle()}
        repeat(10) {i->
            val t=1000L+i*1000
            // The first DOWN can look like a normal mouse click until the driver classifies it.
            assertFalse(send(touchpadEvent(t,t,MotionEvent.ACTION_DOWN,200f,200f,0)))
            assertTrue(send(touchpadEvent(t,t+20,MotionEvent.ACTION_MOVE,200f,130f)))
            assertTrue(send(touchpadEvent(t,t+40,MotionEvent.ACTION_UP,2000f,-20f,0)))
        }
        assertEquals(List(10){1},turns);assertEquals(10,cancels)
        val t=12000L
        assertFalse(send(touchpadEvent(t,t,MotionEvent.ACTION_DOWN,200f,200f,0)))
        assertFalse(send(touchpadEvent(t,t+10,MotionEvent.ACTION_UP,200f,200f,0)))
    }

    @Test @SdkSuppress(minSdkVersion=34)
    fun mousePixelScrollCancelsDownBeforeTurningAndDoesNotHijackPinchOrScreen() {
        val router=router();var cancelled=false;var turns=0
        router.attach(target(turn={assertTrue(cancelled);turns++;true}))
        fun send(e:MotionEvent)=try {router.touch(e) {cancelled=true}} finally {e.recycle()}
        assertFalse(send(touchpadEvent(1000,1000,MotionEvent.ACTION_DOWN,200f,200f,0)))
        assertTrue(send(touchpadEvent(1000,1020,MotionEvent.ACTION_MOVE,200f,200f,0,60f)))
        assertTrue(send(touchpadEvent(1000,1040,MotionEvent.ACTION_CANCEL,200f,200f,0)))
        assertFalse(send(touchpadEvent(2000,2000,MotionEvent.ACTION_DOWN,200f,200f,MotionEvent.CLASSIFICATION_PINCH)))
        assertFalse(send(touchpadEvent(2000,2020,MotionEvent.ACTION_MOVE,200f,100f,MotionEvent.CLASSIFICATION_PINCH)))
        assertFalse(send(touchpadEvent(3000,3000,MotionEvent.ACTION_DOWN,200f,200f,source=InputDevice.SOURCE_TOUCHSCREEN)))
        assertFalse(send(touchpadEvent(3000,3020,MotionEvent.ACTION_MOVE,200f,100f,source=InputDevice.SOURCE_TOUCHSCREEN)))
        assertEquals(1,turns)
    }

    @Test @SdkSuppress(minSdkVersion=34)
    fun gesturesBeginningOnToolbarCannotEnterReadingAndDisabledOwnedStreamDrains() {
        val router=router();var enabled=true;var turns=0
        router.attach(target(enabled={enabled},turn={turns++;true}))
        fun send(e:MotionEvent)=try {router.touch(e)} finally {e.recycle()}
        assertFalse(send(touchpadEvent(1000,1000,MotionEvent.ACTION_DOWN,200f,20f)))
        assertFalse(send(touchpadEvent(1000,1020,MotionEvent.ACTION_MOVE,200f,200f,pixels=60f)))
        assertFalse(send(touchpadEvent(1000,1040,MotionEvent.ACTION_UP,200f,200f,0)))
        assertTrue(send(touchpadEvent(2000,2000,MotionEvent.ACTION_DOWN,200f,200f)))
        enabled=false
        assertTrue(send(touchpadEvent(2000,2020,MotionEvent.ACTION_MOVE,200f,100f)))
        assertTrue(send(touchpadEvent(2000,2040,MotionEvent.ACTION_UP,200f,100f,0)))
        assertEquals(0,turns)
    }
}
