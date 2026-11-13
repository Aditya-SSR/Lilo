package com.lilo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** All the noisy stuff: notifications, beep, vibration, looping alarm on the ALARM stream. */
class AlertManager private constructor(private val ctx: Context) {

    companion object {
        const val CH_MONITOR = "lilo_monitor"
        const val CH_ALERT = "lilo_alert"
        const val ID_FG = 1
        const val ID_ALERT = 2

        @Volatile private var inst: AlertManager? = null
        fun get(c: Context): AlertManager =
            inst ?: synchronized(this) { inst ?: AlertManager(c.applicationContext).also { inst = it } }
    }

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var fallbackTone: ToneGenerator? = null
    private var fallbackLoop: Runnable? = null

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    init {
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Guard status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // we play our own sound
                enableVibration(false)
            }
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun statusNotification(text: String): Notification =
        Notification.Builder(ctx, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_lilo)
            .setContentTitle("Lilo guard")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    fun updateStatus(text: String) = nm.notify(ID_FG, statusNotification(text))

    /** FAR: short vibration pattern + short beep + notification. */
    fun far(child: String) {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 400), -1))
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            handler.postDelayed({ tg.release() }, 800)
        } catch (_: Exception) { }
        nm.notify(
            ID_ALERT,
            Notification.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_lilo)
                .setContentTitle("$child is getting far")
                .setContentText("Signal dropped below your limit.")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    /** LOST or SOS: loud looping alarm + continuous vibration until [stopAlarm]. */
    fun startAlarm(title: String, text: String) {
        if (player != null || fallbackLoop != null) return
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) { }

        val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        var ok = false
        if (uri != null) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(ctx, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                ok = true
            } catch (_: Exception) {
                player?.release()
                player = null
            }
        }
        if (!ok) startFallbackBeeps()

        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 300), 0))

        nm.notify(
            ID_ALERT,
            Notification.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_lilo)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(openApp())
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(ctx, R.drawable.ic_stat_lilo),
                        "I found them",
                        PendingIntent.getService(
                            ctx, 1,
                            Intent(ctx, GuardService::class.java).setAction(GuardService.ACTION_DISMISS),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    ).build()
                )
                .build(),
        )
    }

    private fun startFallbackBeeps() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            fallbackTone = tg
            val loop = object : Runnable {
                override fun run() {
                    tg.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 600)
                    handler.postDelayed(this, 900)
                }
            }
            fallbackLoop = loop
            handler.post(loop)
        } catch (_: Exception) { }
    }

    fun stopAlarm() {
        player?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
        player = null
        fallbackLoop?.let { handler.removeCallbacks(it) }
        fallbackLoop = null
        fallbackTone?.release()
        fallbackTone = null
        vibrator.cancel()
        nm.cancel(ID_ALERT)
    }

    fun clearAlert() = nm.cancel(ID_ALERT)
}
