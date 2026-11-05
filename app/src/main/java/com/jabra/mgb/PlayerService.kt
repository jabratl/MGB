package com.jabra.mgb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * S45: background audio. The ExoPlayer stays inside PlayerActivity; this foreground service (type mediaPlayback)
 * only keeps the process alive while the screen is off / another app is open and shows a notification with
 * Play / Pause and Close. PlayerActivity starts it when playback starts (Settings > Downloads > Background audio)
 * and stops it in onDestroy.
 */
class PlayerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> {
                val a = PlayerActivity.live
                if (a == null) { stopSelf(); return START_NOT_STICKY }
                a.bgToggle() // the Activity calls update() when the playing state changes
                return START_NOT_STICKY
            }
            ACT_CLOSE -> {
                PlayerActivity.live?.bgClose()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val title = intent?.getStringExtra("t")?.ifBlank { null } ?: "Playing"
        val playing = intent?.getBooleanExtra("p", true) ?: true
        val ao = intent?.getBooleanExtra("ao", false) ?: false
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, NOTE_ID, note(title, playing, ao), type)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun act(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, PlayerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun note(title: String, playing: Boolean, audioOnly: Boolean): android.app.Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Player", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(if (playing) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(title)
            .setContentText(if (audioOnly) (if (playing) "Audio only" else "Audio only - paused") else if (playing) "Playing" else "Paused")
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (playing) b.addAction(android.R.drawable.ic_media_pause, "Pause", act(ACT_TOGGLE, 1))
        else b.addAction(android.R.drawable.ic_media_play, "Play", act(ACT_TOGGLE, 1))
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", act(ACT_CLOSE, 2))
        return b.build()
    }

    companion object {
        private const val CH = "mgb_player"
        private const val NOTE_ID = 7002
        const val ACT_TOGGLE = "com.jabra.mgb.PL_TOGGLE"
        const val ACT_CLOSE = "com.jabra.mgb.PL_CLOSE"

        fun on(ctx: Context) = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getBoolean("bg_audio", true)
        fun setOn(ctx: Context, v: Boolean) = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putBoolean("bg_audio", v).apply()

        /** Start or refresh the notification (call while the app is visible). */
        fun update(ctx: Context, title: String, playing: Boolean, audioOnly: Boolean = false) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PlayerService::class.java).putExtra("t", title).putExtra("p", playing).putExtra("ao", audioOnly))
            } catch (e: Exception) { /* background start refused: audio still plays while the process lives */ }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, PlayerService::class.java)) } catch (e: Exception) { }
        }
    }
}
