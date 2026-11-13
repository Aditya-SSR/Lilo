package com.lilo.app.logic

import kotlin.math.roundToInt

enum class GuardState { SAFE, FAR, LOST }

data class GuardSnapshot(val state: GuardState, val raw: Int?, val smoothed: Int?)

/**
 * Pure state machine (no Android deps, easy to unit test). Feed it one sample per tick.
 *
 * - Moving average of the last [windowSize] samples.
 * - SAFE -> FAR when smoothed < threshold for [farAfterMs]; FAR -> SAFE only when smoothed > threshold + [hysteresisDb].
 * - LOST after [lostAfterMs] of null samples. Only armed once a real signal has been seen,
 *   so opening the app with no Wi-Fi doesn't instantly scream.
 */
class GuardEngine(
    var threshold: Int = -75,
    private val windowSize: Int = 5,
    private val farAfterMs: Long = 2_000,
    private val lostAfterMs: Long = 3_000,
    private val hysteresisDb: Int = 5,
) {
    private val window = ArrayDeque<Int>()
    private var belowSince: Long? = null
    private var noSignalSince: Long? = null
    private var armed = false

    var state: GuardState = GuardState.SAFE
        private set

    fun reset() {
        window.clear()
        belowSince = null
        noSignalSince = null
        armed = false
        state = GuardState.SAFE
    }

    fun onSample(rssi: Int?, nowMs: Long): GuardSnapshot {
        if (rssi == null) {
            if (armed) {
                val since = noSignalSince ?: nowMs.also { noSignalSince = it }
                if (nowMs - since >= lostAfterMs) state = GuardState.LOST
            }
            return GuardSnapshot(state, null, null)
        }

        armed = true
        if (noSignalSince != null) {
            // Link is back. If we had been LOST, start fresh so stale samples don't skew the average.
            noSignalSince = null
            if (state == GuardState.LOST) {
                window.clear()
                belowSince = null
                state = GuardState.SAFE
            }
        }

        window.addLast(rssi)
        if (window.size > windowSize) window.removeFirst()
        val avg = window.average().roundToInt()

        if (avg < threshold) {
            val since = belowSince ?: nowMs.also { belowSince = it }
            if (state == GuardState.SAFE && nowMs - since >= farAfterMs) state = GuardState.FAR
        } else {
            belowSince = null
            if (state == GuardState.FAR && avg > threshold + hysteresisDb) state = GuardState.SAFE
        }
        return GuardSnapshot(state, rssi, avg)
    }
}
