package com.lilo.app

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service so monitoring (and the alarm) keeps working with the screen off. */
class GuardService : Service() {

    companion object {
        const val ACTION_DISMISS = "com.lilo.app.DISMISS"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
            } catch (e: Exception) {
                Log.w("Lilo", "Could not start guard service", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GuardService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GuardHub.init(this)
        if (intent?.action == ACTION_DISMISS) {
            GuardHub.dismissAlarm(this)
            return START_STICKY
        }

        // Include the location type only when the permission is granted (required on API 34+).
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            ServiceCompat.startForeground(this, AlertManager.ID_FG, AlertManager.get(this).statusNotification("Starting…"), type)
        } catch (e: Exception) {
            Log.w("Lilo", "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lilo:guard").apply { acquire() }
        }
        if (job == null) {
            GuardHub.running.value = true
            job = scope.launch { GuardHub.monitor(applicationContext) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        job = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        GuardHub.running.value = false
        GuardHub.onStopped(this)
        super.onDestroy()
    }
}
