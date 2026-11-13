package com.lilo.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    @Test fun presetsMapToRoundDistances() {
        assertEquals("5 m", Units.limitLabel(-60, meters = true))
        assertEquals("10 m", Units.limitLabel(-70, meters = true))
        assertEquals("20 m", Units.limitLabel(-80, meters = true))
    }

    @Test fun roundTripIsStable() {
        assertEquals(-60, Units.metersToDbm(5.0))
        assertEquals(-70, Units.metersToDbm(10.0))
        assertEquals(-80, Units.metersToDbm(20.0))
    }

    @Test fun dbmModeKeepsRawNumbers() {
        assertEquals("-62 dBm", Units.signalLabel(-62, meters = false))
        assertEquals("-- dBm", Units.signalLabel(null, meters = false))
        assertEquals("-- m", Units.signalLabel(null, meters = true))
    }
}
