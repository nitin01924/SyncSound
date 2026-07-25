package com.syncsound.app.service

import android.app.Notification
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
import com.syncsound.app.MainActivity
import com.syncsound.app.R
import com.syncsound.app.logging.AppLog
import com.syncsound.app.logging.LogArea

/**
 * Keeps an active local-network audio session eligible to run when the display
 * sleeps or the UI is backgrounded. Session state remains in the repository.
 */
class SessionForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active SyncSound session",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        } catch (error: Exception) {
            AppLog.e(LogArea.SESSION, "foreground_service_failed", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SyncSound is active")
            .setContentText("Keeping this phone connected to the local audio session")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "syncsound_session"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context): Boolean = runCatching {
            context.startForegroundService(Intent(context, SessionForegroundService::class.java))
            true
        }.onFailure {
            AppLog.e(LogArea.SESSION, "foreground_service_start_failed", it)
        }.getOrDefault(false)

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }
    }
}
