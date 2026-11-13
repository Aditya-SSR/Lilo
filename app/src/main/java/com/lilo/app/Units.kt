package com.lilo.app

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Converts Wi-Fi signal strength (dBm) to an estimated distance (metres) with the log-distance path-loss model:
 *   d = 10 ^ ((RSSI_AT_1M - rssi) / (10 * PATH_LOSS))
 * The constants are tuned for a typical indoor phone hotspot so that -60 / -70 / -80 dBm come out near 5 / 10 / 20 m.
 * It is an estimate: walls, bodies and different phones shift it. All logic still runs on dBm.
 */
object Units {
    private const val RSSI_AT_1M = -37.0
    private const val PATH_LOSS = 3.3

    fun dbmToMeters(dbm: Int): Double = 10.0.pow((RSSI_AT_1M - dbm) / (10 * PATH_LOSS))

    fun metersToDbm(meters: Double): Int =
        (RSSI_AT_1M - 10 * PATH_LOSS * log10(meters.coerceAtLeast(0.5))).roundToInt()

    private fun wholeMeters(dbm: Int) = dbmToMeters(dbm).roundToInt().coerceAtLeast(1)

    /** Live reading, e.g. "~8 m" or "-62 dBm". */
    fun signalLabel(dbm: Int?, meters: Boolean): String = when {
        dbm == null -> if (meters) "-- m" else "-- dBm"
        meters -> "~${wholeMeters(dbm)} m"
        else -> "$dbm dBm"
    }

    /** A limit / preset, e.g. "10 m" or "-70 dBm". */
    fun limitLabel(dbm: Int, meters: Boolean): String =
        if (meters) "${wholeMeters(dbm)} m" else "$dbm dBm"
}
