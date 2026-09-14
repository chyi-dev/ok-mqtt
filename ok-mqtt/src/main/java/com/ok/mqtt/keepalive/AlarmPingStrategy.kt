package com.ok.mqtt.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Schedules exact (or inexact) alarms to wake the CPU so HiveMQ's Netty keepalive can run.
 */
class AlarmPingStrategy(
    private val context: Context
) : KeepaliveStrategy {

    companion object {
        private const val TAG = "AlarmPingStrategy"
        const val ACTION = "com.ok.mqtt.keepalive.ALARM_PING"

        @Volatile
        internal var handleRef: KeepaliveHandle? = null

        internal fun schedule(context: Context, handle: KeepaliveHandle) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val interval = handle.keepAliveIntervalMs().coerceAtLeast(5_000L)
            val intent = Intent(appContext, AlarmPingReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
            val triggerAt = SystemClock.elapsedRealtime() + interval
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    @Suppress("DEPRECATION")
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm denied, falling back", e)
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }

        internal fun cancel(context: Context) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(appContext, AlarmPingReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
            alarmManager.cancel(pi)
        }
    }

    private val appContext = context.applicationContext

    override fun start(handle: KeepaliveHandle) {
        handleRef = handle
        schedule(appContext, handle)
    }

    override fun stop() {
        cancel(appContext)
        handleRef = null
    }

    override fun onForeground() {
        handleRef?.let { schedule(appContext, it) }
    }

    override fun onBackground() {
        handleRef?.let { schedule(appContext, it) }
    }
}

class AlarmPingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AlarmPingStrategy.ACTION) return
        val handle = AlarmPingStrategy.handleRef ?: return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ok-mqtt:alarm-rx")
        try {
            wl.acquire(10_000L)
            handle.onWakeRequested()
            handle.onLivenessCheckRequested()
        } finally {
            if (wl.isHeld) wl.release()
        }
        AlarmPingStrategy.schedule(context, handle)
    }
}
