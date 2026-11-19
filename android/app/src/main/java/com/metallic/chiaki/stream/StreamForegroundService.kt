package com.metallic.chiaki.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.metallic.chiaki.R

class StreamForegroundService : Service()
{
    companion object
    {
        const val ACTION_END_CONNECTION = "com.metallic.chiaki.action.END_CONNECTION"
        const val ACTION_RESYNC = "com.metallic.chiaki.action.RESYNC"
        const val ACTION_TOGGLE_AUDIO = "com.metallic.chiaki.action.TOGGLE_AUDIO"
        private const val CHANNEL_ID = "steam2ps_stream_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        when(intent?.action)
        {
            ACTION_END_CONNECTION -> {
                sendBroadcast(Intent(ACTION_END_CONNECTION))
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                else
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESYNC -> {
                sendBroadcast(Intent(ACTION_RESYNC))
                return START_STICKY
            }
            ACTION_TOGGLE_AUDIO -> {
                sendBroadcast(Intent(ACTION_TOGGLE_AUDIO))
                return START_STICKY
            }
        }

        createNotificationChannel()

        val notificationIntent = Intent(this, StreamActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resyncIntent = Intent(this, StreamForegroundService::class.java).apply { action = ACTION_RESYNC }
        val resyncPendingIntent = PendingIntent.getService(this, 1, resyncIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val audioIntent = Intent(this, StreamForegroundService::class.java).apply { action = ACTION_TOGGLE_AUDIO }
        val audioPendingIntent = PendingIntent.getService(this, 2, audioIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val endIntent = Intent(this, StreamForegroundService::class.java).apply { action = ACTION_END_CONNECTION }
        val endPendingIntent = PendingIntent.getService(this, 0, endIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Steam 2 PS")
            .setContentText("Remote Play streaming is active in background")
            .setSmallIcon(R.drawable.ic_gamepad)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_rotate, "Resync", resyncPendingIntent)
            .addAction(android.R.drawable.ic_lock_silent_mode_off, "Audio", audioPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", endPendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    private fun createNotificationChannel()
    {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Steam 2 PS Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
