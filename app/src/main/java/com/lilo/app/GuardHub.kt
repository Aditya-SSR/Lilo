package com.lilo.app

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.lilo.app.logic.GuardEngine
import com.lilo.app.logic.GuardSnapshot
import com.lilo.app.logic.GuardState
import com.lilo.app.signal.SimSource
import com.lilo.app.signal.WifiSource
import com.lilo.app.esp.EspClient
import com.lilo.app.esp.EspStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class Settings(
    val threshold: Int = -75,
    val useWifi: Boolean = false,
    val ssidFilter: String = "",
    val simRssi: Int = -50,
    val simDropped: Boolean = false,
    val useMeters: Boolean = true,
)

data class Profile(
    val guardianName: String = "",
    val guardianPhone: String = "",
    val childName: String = "",
)

/**
 * App-wide state shared between the UI and the foreground service.
 * The service runs [monitor]; the UI just observes the flows and calls the setters.
 */
object GuardHub {
    private val engine = GuardEngine()
    private var prefs: SharedPreferences? = null

    val settings = MutableStateFlow(Settings())
    val profile = MutableStateFlow(Profile())
    val running = MutableStateFlow(false)
    val snapshot = MutableStateFlow(GuardSnapshot(GuardState.SAFE, null, null))
    val alarmActive = MutableStateFlow(false)
    val currentSsid = MutableStateFlow<String?>(null)
    val wifiLinked = MutableStateFlow(false)

    /** True after the user taps "Stop guard", so reopening the app doesn't silently restart it. */
    @Volatile var userStopped = false

    // ---- GuardTag (ESP32) extras. Everything here is optional: if the tag is absent these stay empty. ----
    /** Latest answer from the tag's /status, or null when no tag is detected. */
    val espStatus = MutableStateFlow<EspStatus?>(null)
    val sosActive = MutableStateFlow(false)
    private var appCtx: Context? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var sosSuppressed = false   // SOS was dismissed here but the tag still reports it
    private fun esp() = EspClient(appCtx!!)

    // ---- persistence ----
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        if (prefs != null) return
        val p =ctx.applicationContext.getSharedPreferences("lilo", Context.MODE_PRIVATE)
        prefs = p
        settings.value = Settings(
            threshold = p.getInt("threshold", -75),
            useWifi = p.getBoolean("useWifi", false),
            ssidFilter = p.getString("ssid", "") ?: "",
            useMeters = p.getBoolean("useMeters", true),
        )
        profile.value = Profile(
            guardianName = p.getString("gName", "") ?: "",
            guardianPhone = p.getString("gPhone", "") ?: "",
            childName = p.getString("cName", "") ?: "",
        )
    }

    // ---- UI actions ----
    fun setThreshold(dbm: Int) {
        val t = dbm.coerceIn(-100, -40)
        settings.update { it.copy(threshold = t) }
        prefs?.edit()?.putInt("threshold", t)?.apply()
    }

    /** "Set limit here": stand at the max distance you accept and press. */
    fun setLimitHere() {
        // Never set the limit tighter than 3 m, otherwise normal signal wobble would trigger constant alerts.
        val tightest = Units.metersToDbm(3.0)
        snapshot.value.smoothed?.let { setThreshold(minOf(it, tightest)) }
    }

    fun setUseWifi(wifi: Boolean) {
        settings.update { it.copy(useWifi = wifi) }
        prefs?.edit()?.putBoolean("useWifi", wifi)?.apply()
    }

    fun setUseMeters(meters: Boolean) {
        settings.update { it.copy(useMeters = meters) }
        prefs?.edit()?.putBoolean("useMeters", meters)?.apply()
    }

    fun setSsidFilter(s: String) {
        settings.update { it.copy(ssidFilter = s) }
        prefs?.edit()?.putString("ssid", s)?.apply()
    }

    fun setSimRssi(v: Int) = settings.update { it.copy(simRssi = v) }
    fun setSimDropped(d: Boolean) = settings.update { it.copy(simDropped = d) }

    fun saveProfile(p: Profile) {
        profile.value = p
        prefs?.edit()?.putString("gName", p.guardianName)?.putString("gPhone", p.guardianPhone)
            ?.putString("cName", p.childName)?.apply()
        if (espStatus.value != null) ioScope.launch { esp().pushInfo(p.guardianName, p.guardianPhone) }
    }

    /** "I found them" */
    fun dismissAlarm(ctx: Context) {
        AlertManager.get(ctx).stopAlarm()
        alarmActive.value = false
        // Parent says the child is found: also clear LOST mode on the tag (if it is reachable).
        if (espStatus.value != null) ioScope.launch { esp().ack() }
    }

    /** Dismiss the SOS banner and tell the tag to clear its SOS flag. */
    fun dismissSos(ctx: Context) {
        sosActive.value = false
        sosSuppressed = true
        if (!alarmActive.value) AlertManager.get(ctx).stopAlarm()
        ioScope.launch { esp().ack() }
    }

    /** "Find child": the tag flashes its LEDs and LCD for 5 seconds. */
    fun pingTag() {
        ioScope.launch { esp().ping() }
    }

    // ---- service side ----
    private fun childName() = profile.value.childName.ifBlank { "Your child" }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun monitor(ctx: Context) = coroutineScope {
        val alerts = AlertManager.get(ctx)
        val wifi = WifiSource(ctx, { settings.value.ssidFilter }, { currentSsid.value = it }, { wifiLinked.value = it })
        val sim = SimSource(settings)
        val latest = AtomicReference<Int?>(null)
        val ready = AtomicBoolean(false)

        launch {
            settings.map { it.useWifi }.distinctUntilChanged()
                .flatMapLatest { if (it) wifi.rssi() else sim.rssi() }
                .collect {
                    latest.set(it)
                    ready.set(true)
                }
        }

        launch(Dispatchers.IO) { espLoop(ctx, alerts) }

        engine.reset()
        var prev = GuardState.SAFE
        alerts.updateStatus("Watching…")
        while (isActive) {
            if (ready.get()) {
                engine.threshold = settings.value.threshold
                val snap = engine.onSample(latest.get(), SystemClock.elapsedRealtime())
                snapshot.value = snap

                if (snap.state != prev) {
                    when (snap.state) {
                        GuardState.FAR -> alerts.far(childName())
                        GuardState.LOST -> {
                            alarmActive.value = true
                            alerts.startAlarm("${childName()} is out of range!", "Connection lost. Tap to open Lilo.")
                        }
                        GuardState.SAFE -> if (!alarmActive.value) alerts.clearAlert()
                    }
                    alerts.updateStatus(
                        when (snap.state) {
                            GuardState.SAFE -> "${childName()} is safe"
                            GuardState.FAR -> "${childName()} is getting far"
                            GuardState.LOST -> "${childName()} is out of range!"
                        }
                    )
                    prev = snap.state
                }
            }
            delay(1000)
        }
    }

    /**
     * Polls the tag about once a second while we are on Wi-Fi. The poll doubles as the tag's heartbeat.
     * Backs off to every 5 s when no tag answers (e.g. the "tag" is just a phone hotspot).
     */
    private suspend fun espLoop(ctx: Context, alerts: AlertManager) {
        val client = EspClient(ctx)
        var failures = 0
        var wasOnline = false
        while (currentCoroutineContext().isActive) {
            if (!settings.value.useWifi || !wifiLinked.value) {
                // Leaving Wi-Fi mode on purpose = guard off: tell the tag so it returns to pairing instead of raising LOST.
                // If the link just dropped we can't reach the tag, and that is exactly when it should raise LOST itself.
                if (wasOnline && !settings.value.useWifi) client.pushState("OFF")
                espStatus.value = null
                wasOnline = false
                failures = 0
                delay(1000)
                continue
            }
            val st = client.status()
            if (st != null) {
                failures = 0
                espStatus.value = st
                if (!wasOnline) {
                    wasOnline = true
                    client.pushInfo(profile.value.guardianName, profile.value.guardianPhone)
                }
                val mine = snapshot.value
                if (mine.smoothed != null && mine.state.name != st.state) client.pushState(mine.state.name)

                if (st.sos) {
                    if (sosSuppressed) client.ack()
                    else if (!sosActive.value) {
                        sosActive.value = true
                        alerts.startAlarm("SOS from ${childName()}!", "The tag's SOS button was pressed.")
                    }
                } else {
                    sosSuppressed = false
                }
            } else if (++failures >= 3) {
                espStatus.value = null
                wasOnline = false
            }
            delay(if (failures >= 3) 5000 else 1000)
        }
    }

    fun onStopped(ctx: Context) {
        // Guard switched off on purpose: tell the tag so it goes back to pairing mode instead of raising LOST.
        if (espStatus.value != null) ioScope.launch { esp().pushState("OFF") }
        espStatus.value = null
        sosActive.value = false
        sosSuppressed = false
        engine.reset()
        snapshot.value = GuardSnapshot(GuardState.SAFE, null, null)
        currentSsid.value = null
        wifiLinked.value = false
        dismissAlarm(ctx)
    }
}
