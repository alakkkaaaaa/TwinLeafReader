package com.localreader.twinleaf.input

import android.graphics.RectF
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration

enum class ReaderNavigationKey(val direction: Int) {
    LEFT(-1), RIGHT(1), UP(-1), DOWN(1), PAGE_UP(-1), PAGE_DOWN(1);

    companion object {
        fun from(code: Int): ReaderNavigationKey? = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
            else -> null
        }
    }
}

/** One scoped reading viewport, independent of whichever native/Compose view last took focus. */
class ReaderInputTarget(
    val enabled: () -> Boolean,
    val bounds: () -> RectF?,
    val paginated: Boolean,
    val navigate: (ReaderNavigationKey) -> Boolean,
    val turn: (Int) -> Boolean,
    val scroll: (Float, Float, Long) -> Boolean,
)

class ReaderInputRouter(configuration: ViewConfiguration, density: Float) {
    private val horizontalFactor = configuration.scaledHorizontalScrollFactor
    private val verticalFactor = configuration.scaledVerticalScrollFactor
    private val gate = ScrollPageGate(48f * density)
    private var target: ReaderInputTarget? = null
    private val keys = mutableMapOf<Int, Long>()
    private var lastRepeat = Long.MIN_VALUE
    private var scrollDevice: Int? = null
    private var touchDevice: Int? = null
    private var touchDownTime = 0L
    private var touchOwned = false
    private var touchAllowed = false
    private var lastX = 0f
    private var lastY = 0f

    fun attach(value: ReaderInputTarget) { reset(); target = value }
    fun detach(value: ReaderInputTarget) { if (target === value) { reset(); target = null } }
    fun reset() { gate.reset(); keys.clear(); lastRepeat = Long.MIN_VALUE; scrollDevice = null; clearTouch() }

    private fun clearTouch() { touchDevice = null; touchOwned = false; touchAllowed = false }

    /** Touchpads normally report SOURCE_MOUSE. Their classified swipes use the touch
     * dispatch path, unlike ACTION_SCROLL. Never infer a swipe from a touchscreen or a click. */
    fun touch(event: MotionEvent, cancelChild: () -> Unit = {}): Boolean {
        if (event.pointerCount == 0 ||
            !(event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) ||
            (0 until event.pointerCount).any { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS ||
                event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }) return false
        val action = event.actionMasked
        if (action !in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) return false
        val same = touchDevice == event.deviceId && touchDownTime == event.downTime
        val current = target
        if (action == MotionEvent.ACTION_DOWN || !same) {
            clearTouch(); gate.reset()
            touchDevice = event.deviceId; touchDownTime = event.downTime
            val area = current?.bounds()
            touchAllowed = current?.enabled() == true && area != null &&
                (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) || area.contains(event.x, event.y))
            lastX = event.x; lastY = event.y
        }
        val terminal = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val classification = if (Build.VERSION.SDK_INT >= 29) event.classification else 0
        val pixelAxes = Build.VERSION.SDK_INT >= 34 &&
            (event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE) != 0f ||
                event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE) != 0f ||
                (0 until event.historySize).any {
                    event.getHistoricalAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE, 0, it) != 0f ||
                        event.getHistoricalAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, 0, it) != 0f
                })
        val scrolling = (Build.VERSION.SDK_INT >= 34 && classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) ||
            (classification == 0 && pixelAxes)
        if (!touchOwned && touchAllowed && current?.enabled() == true && scrolling &&
            action != MotionEvent.ACTION_CANCEL && event.metaState and
                (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK) == 0) {
            // Cancel a DOWN previously delivered before gesture classification became available.
            cancelChild()
            touchOwned = true
        }
        val consumed = touchOwned
        if (consumed && current?.enabled() == true && scrolling && action != MotionEvent.ACTION_CANCEL) {
            if (pixelAxes && Build.VERSION.SDK_INT >= 34) {
                for (h in 0 until event.historySize) deliver(current,
                    event.getHistoricalAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE, 0, h),
                    event.getHistoricalAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, 0, h),
                    event.getHistoricalEventTime(h))
                deliver(current, event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE),
                    event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE), event.eventTime)
            } else if (action == MotionEvent.ACTION_MOVE) {
                // Compatibility streams represent the swipe as a moving virtual touch point.
                for (h in 0 until event.historySize) {
                    val x = event.getHistoricalX(0, h); val y = event.getHistoricalY(0, h)
                    deliver(current, lastX - x, lastY - y, event.getHistoricalEventTime(h))
                    lastX = x; lastY = y
                }
                deliver(current, lastX - event.x, lastY - event.y, event.eventTime)
            }
        }
        lastX = event.x; lastY = event.y
        // UP/CANCEL may lose classification and carry zero axes or out-of-window coordinates.
        if (terminal) { clearTouch(); gate.reset() }
        return consumed
    }

    fun key(event: KeyEvent): Boolean {
        val command = ReaderNavigationKey.from(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_UP) return keys.remove(event.keyCode) == event.downTime
        val current = target ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.isCanceled || !current.enabled() ||
            event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || event.isShiftPressed) return false
        keys[event.keyCode] = event.downTime
        if (event.repeatCount > 0 && lastRepeat != Long.MIN_VALUE && event.eventTime - lastRepeat < 120) return true
        lastRepeat = event.eventTime
        current.navigate(command)
        return true // Boundary / busy requests must not fall through to toolbar focus movement.
    }

    fun motion(event: MotionEvent, cancelChild: () -> Unit = {}): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return touch(event, cancelChild)
        val current = target ?: return false
        if (event.pointerCount == 0 ||
            !current.enabled() || event.metaState and
                (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK) != 0 ||
            !(event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) ||
            (0 until event.pointerCount).any { event.getToolType(it) in
                listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER) }) return false
        val area = current.bounds() ?: return false
        // SOURCE_TOUCHPAD can carry pad-relative coordinates, not the window cursor position.
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && !area.contains(event.x, event.y)) return false
        val x = event.getAxisValue(MotionEvent.AXIS_HSCROLL) * horizontalFactor
        val y = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * verticalFactor
        if (!x.isFinite() || !y.isFinite() || (x == 0f && y == 0f)) return false
        if (touchDevice == event.deviceId && touchAllowed && !touchOwned) {
            cancelChild(); touchOwned = true
        }
        if (scrollDevice != event.deviceId) { gate.reset(); scrollDevice = event.deviceId }
        for (history in 0 until event.historySize) deliver(current,
            event.getHistoricalAxisValue(MotionEvent.AXIS_HSCROLL, 0, history) * horizontalFactor,
            -event.getHistoricalAxisValue(MotionEvent.AXIS_VSCROLL, 0, history) * verticalFactor,
            event.getHistoricalEventTime(history))
        return deliver(current, x, y, event.eventTime)
    }

    private fun deliver(current: ReaderInputTarget, x: Float, y: Float, time: Long): Boolean {
        if (!x.isFinite() || !y.isFinite() || (x == 0f && y == 0f)) return false
        if (current.paginated) {
            gate.scroll(x, y, time, current.turn)
            return true
        }
        gate.reset()
        return current.scroll(x, y, time)
    }
}
