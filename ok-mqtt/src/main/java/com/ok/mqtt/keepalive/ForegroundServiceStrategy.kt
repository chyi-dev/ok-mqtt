package com.ok.mqtt.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Starts a dataSync foreground service to keep the process alive while connected.
 */
class ForegroundServiceStrategy(
    private val context: Context,
    private val config: KeepaliveConfig
) : KeepaliveStrategy {

    companion object {
        private const val TAG = "FgsKeepalive"
        @Volatile
        internal var running = false
    }

    override fun start(handle: KeepaliveHandle) {
        try {
            val intent = Intent(context, MqttKeepaliveService::class.java).apply {
                putExtra(MqttKeepaliveService.EXTRA_TITLE, config.notificationTitle)
                putExtra(MqttKeepaliveService.EXTRA_TEXT, config.notificationText)
                putExtra(MqttKeepaliveService.EXTRA_CHANNEL, config.notificationChannelId)
                putExtra(MqttKeepaliveService.EXTRA_NOTIF_ID, config.notificationId)
            }
            ContextCompat.startForegroundService(context, intent)
            running = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground service (permission/channel?)", e)
            running = false
        }
    }

    override fun stop() {
        if (!running) return
        runCatching {
            context.stopService(Intent(context, MqttKeepaliveService::class.java))
        }
        running = false
    }
}

class MqttKeepaliveService : Service() {
    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_NOTIF_ID = "notifId"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "MQTT"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Connected"
        val channelId = intent?.getStringExtra(EXTRA_CHANNEL) ?: "ok_mqtt_keepalive"
        val notifId = intent?.getIntExtra(EXTRA_NOTIF_ID, 0x4D5154) ?: 0x4D5154
        ensureChannel(channelId)
        val notification = buildNotification(channelId, title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notifId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(notifId, notification)
        }
        ForegroundServiceStrategy.running = true
        return START_STICKY
    }

    override fun onDestroy() {
        ForegroundServiceStrategy.running = false
        super.onDestroy()
    }

    private fun ensureChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            "MQTT Keepalive",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps MQTT connection alive in background"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(channelId: String, title: String, text: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
