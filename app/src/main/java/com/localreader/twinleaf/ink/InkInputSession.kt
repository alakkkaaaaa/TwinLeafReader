package com.localreader.twinleaf.ink

/** A stroke owns exactly one device/pointer/gesture. Late batched samples cannot cross it. */
internal class InkInputSession {
    var deviceId = -1; private set
    private var pointerId = -1
    private var downTime = Long.MIN_VALUE
    private var startedAt = Long.MIN_VALUE
    private var lastSample = Long.MIN_VALUE
    fun begin(device: Int, pointer: Int, gesture: Long, time: Long) {
        deviceId = device; pointerId = pointer; downTime = gesture
        startedAt = time; lastSample = Long.MIN_VALUE
    }
    fun owns(device: Int, pointer: Int, gesture: Long) =
        pointerId >= 0 && deviceId == device && pointerId == pointer && downTime == gesture
    fun acceptTime(time: Long): Boolean {
        if (pointerId < 0 || time < startedAt || time <= lastSample) return false
        lastSample = time
        return true
    }
    fun clear() { deviceId = -1; pointerId = -1; downTime = Long.MIN_VALUE; lastSample = Long.MIN_VALUE }
}
