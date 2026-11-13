package com.localreader.twinleaf

import android.view.InputDevice
import android.view.MotionEvent
import androidx.annotation.RequiresApi

/** A touch-dispatched SOURCE_MOUSE swipe, rather than the wheel-only fixture in 3.0.2. */
@RequiresApi(34)
internal fun touchpadEvent(down:Long,time:Long,action:Int,x:Float,y:Float,
                          classification:Int=MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE,
                          pixels:Float=0f,source:Int=InputDevice.SOURCE_MOUSE):MotionEvent =
    MotionEvent.obtain(down,time,action,1,
        arrayOf(MotionEvent.PointerProperties().apply {id=0;toolType=MotionEvent.TOOL_TYPE_FINGER}),
        arrayOf(MotionEvent.PointerCoords().apply {
            this.x=x;this.y=y
            setAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE,pixels)
        }),0,0,1f,1f,29111,0,source,0,0,classification)!!
