package com.newash.videocast.server

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import java.io.IOException
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.newash.videocast.App
import com.newash.videocast.MainActivity
import com.newash.videocast.R

/**
 * Foreground service that keeps the HTTP server (and the Wi-Fi radio) alive
 * while the screen is off. The Cast framework shows its own media notification;
 * this one only exists to satisfy the foreground-service requirement.
 */
class StreamingService : Service() {

    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "videocast:server")
            .apply { setReferenceCounted(false) }
    }

    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videocast:server")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, App.STREAMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.streaming_notification_title))
            .setContentText(getString(R.string.streaming_notification_text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        try {
            ServerHolder.ensureStarted(applicationContext)
        } catch (_: IOException) {
            // No ports available — the ViewModel surfaces its own error; don't crash.
            stopSelf()
            return START_NOT_STICKY
        }
        wifiLock.acquire()
        wakeLock.acquire()
        // Not sticky: after process death the ViewModel state (and thus the served
        // video) is gone, so a restarted service would back a 404-only server.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wifiLock.release()
        wakeLock.release()
        ServerHolder.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, StreamingService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamingService::class.java))
        }
    }
}
