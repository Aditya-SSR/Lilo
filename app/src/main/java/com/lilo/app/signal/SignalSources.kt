package com.lilo.app.signal

import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import com.lilo.app.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** A source of signal strength in dBm. null = no link. */
interface SignalSource {
    fun rssi(): Flow<Int?>
}

/** Backup/dev source: a slider value plus a "drop link" switch. */
class SimSource(private val settings: StateFlow<Settings>) : SignalSource {
    override fun rssi(): Flow<Int?> =
        settings.map { if (it.simDropped) null else it.simRssi }.distinctUntilChanged()
}

/** Reads the RSSI of the Wi-Fi network the phone is currently connected to, once per second. */
class WifiSource(
    context: Context,
    private val ssidFilter: () -> String,
    private val onSsid: (String?) -> Unit,
    private val onLinked: (Boolean) -> Unit,
) : SignalSource {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    override fun rssi(): Flow<Int?> = flow {
        while (true) {
            emit(read())
            delay(1000)
        }
    }

    @Suppress("DEPRECATION") // connectionInfo is deprecated on API 31+ but still works
    private fun read(): Int? {
        if (!wifi.isWifiEnabled) { onSsid(null); onLinked(false); return null }
        val info = wifi.connectionInfo
        if (info == null || info.rssi <= -127 ||
            (info.networkId == -1 && info.supplicantState != SupplicantState.COMPLETED)
        ) {
            onSsid(null)
            onLinked(false)
            return null
        }
        // The network NAME is hidden by Android when Location is off, but the signal still works.
        val ssid = info.ssid?.trim('"')?.takeUnless { it == WifiManager.UNKNOWN_SSID }
        onSsid(ssid)
        onLinked(true)
        val want = ssidFilter().trim()
        if (want.isNotEmpty() && ssid != want) return null // connected to some other network
        return info.rssi
    }
}
