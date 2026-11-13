package com.lilo.app.esp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class EspStatus(val id: String, val sos: Boolean, val state: String, val uptimeSec: Long)

/**
 * Talks to the GuardTag firmware (ESP32) at 192.168.4.1.
 * Requests are bound to the Wi-Fi network, so they still work when the tag's hotspot has no internet
 * and Android would otherwise send traffic over mobile data. Every call returns null/false on failure and never throws.
 */
class EspClient(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun get(path: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://192.168.4.1$path")
            val net = wifiNetwork()
            conn = (if (net != null) net.openConnection(url) else url.openConnection()) as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            Log.w("LiloEsp", "request $path failed: $e")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Also the heartbeat: the tag shows NO LINK if it hears nothing for 6 s. */
    fun status(): EspStatus? {
        val body = get("/status?app=1") ?: return null   // app=1 tells the tag a parent is connected (arms LOST mode)
        return try {
            val j = JSONObject(body)
            EspStatus(j.optString("id"), j.optBoolean("sos"), j.optString("state", "SAFE"), j.optLong("uptime"))
        } catch (_: Exception) {
            null
        }
    }

    fun pushState(state: String): Boolean = get("/state?s=$state") != null
    fun pushInfo(name: String, phone: String): Boolean = get("/info?name=${enc(name)}&phone=${enc(phone)}") != null
    fun ping(): Boolean = get("/ping") != null
    fun ack(): Boolean = get("/ack") != null
}
