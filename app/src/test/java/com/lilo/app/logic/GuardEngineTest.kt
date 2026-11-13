package com.lilo.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardEngineTest {

    private fun GuardEngine.feed(rssi: Int?, fromSec: Int, toSec: Int): GuardSnapshot {
        var snap = GuardSnapshot(state, null, null)
        for (s in fromSec..toSec) snap = onSample(rssi, s * 1000L)
        return snap
    }

    @Test fun strongSignalStaysSafe() {
        val e = GuardEngine(threshold = -75)
        assertEquals(GuardState.SAFE, e.feed(-50, 0, 10).state)
    }

    @Test fun farOnlyAfterTwoSecondsBelow() {
        val e = GuardEngine(threshold = -75)
        e.feed(-90, 0, 1)
        assertEquals(GuardState.SAFE, e.state)
        e.onSample(-90, 2000)
        assertEquals(GuardState.FAR, e.state)
    }

    @Test fun hysteresisKeepsFarUntilFiveDbAbove() {
        val e = GuardEngine(threshold = -75)
        e.feed(-90, 0, 3)
        assertEquals(GuardState.FAR, e.state)
        e.feed(-72, 4, 12) // above T but not above T+5
        assertEquals(GuardState.FAR, e.state)
        e.feed(-60, 13, 20) // well above T+5
        assertEquals(GuardState.SAFE, e.state)
    }

    @Test fun lostAfterThreeSecondsOfNoSignal() {
        val e = GuardEngine()
        e.feed(-50, 0, 2)
        e.onSample(null, 3000)
        e.onSample(null, 4000)
        e.onSample(null, 5000)
        assertEquals(GuardState.SAFE, e.state)
        val snap = e.onSample(null, 6000)
        assertEquals(GuardState.LOST, snap.state)
        assertNull(snap.smoothed)
    }

    @Test fun recoversFromLost() {
        val e = GuardEngine()
        e.feed(-50, 0, 2)
        e.feed(null, 3, 7)
        assertEquals(GuardState.LOST, e.state)
        assertEquals(GuardState.SAFE, e.onSample(-50, 8000).state)
    }

    @Test fun neverLostIfNoSignalWasEverSeen() {
        val e = GuardEngine()
        assertEquals(GuardState.SAFE, e.feed(null, 0, 30).state)
    }

    @Test fun movingAverageOfLastFive() {
        val e = GuardEngine()
        var snap = GuardSnapshot(GuardState.SAFE, null, null)
        listOf(-50, -50, -50, -50, -50, -60, -60, -60, -60, -60).forEachIndexed { i, v ->
            snap = e.onSample(v, i * 1000L)
        }
        assertEquals(-60, snap.smoothed)
    }
}
