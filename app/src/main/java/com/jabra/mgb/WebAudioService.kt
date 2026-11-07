package com.jabra.mgb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 0.0.296: Media > Background play. Only keeps the process alive while a page plays sound and Thazir is not in front.
 * Started by MediaPrefs.onPause, stopped when Thazir comes back, on Stop, or after sound has been silent for ~12 s.
 */
class WebAudioService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var quiet = 0
    private val tick = object : Runnable {
        override fun run() {
            quiet = if (MediaPrefs.audible(this@WebAudioService)) 0 else quiet + 1
            if (quiet >= 2) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } else h.postDelayed(this, 6000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_STOP) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Background play", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, WebAudioService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_play).setContentTitle("Thazir is playing media").setContentText("Background play is on")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, "Stop", stop).build()
        try {
            ServiceCompat.startForeground(this, NOTE_ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        h.removeCallbacks(tick); quiet = 0; h.postDelayed(tick, 6000)
        return START_NOT_STICKY
    }

    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }

    companion object { private const val CH = "mgb_webaudio"; private const val NOTE_ID = 7003; private const val ACT_STOP = "com.jabra.mgb.WEBAUDIO_STOP" }
}
