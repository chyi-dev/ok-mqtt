package com.ok.mqtt.keepalive

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based fallback wakeup. Not the primary ping source.
 */
class WorkManagerStrategy(
    private val context: Context
) : KeepaliveStrategy {

    companion object {
        internal const val UNIQUE = "ok_mqtt_keepalive_wm"
        @Volatile
        var handleRef: KeepaliveHandle? = null
    }

    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun start(handle: KeepaliveHandle) {
        handleRef = handle
        schedule(handle.keepAliveIntervalMs())
    }

    override fun stop() {
        workManager.cancelUniqueWork(UNIQUE)
        handleRef = null
    }

    override fun onForeground() {
        handleRef?.let { schedule(it.keepAliveIntervalMs()) }
    }

    override fun onBackground() {
        handleRef?.let { schedule(it.keepAliveIntervalMs()) }
    }

    private fun schedule(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<KeepaliveWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(15_000L), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
    }
}

class KeepaliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val handle = WorkManagerStrategy.handleRef ?: return Result.success()
        handle.onWakeRequested()
        handle.onLivenessCheckRequested()
        // Reschedule
        val request = OneTimeWorkRequestBuilder<KeepaliveWorker>()
            .setInitialDelay(handle.keepAliveIntervalMs().coerceAtLeast(15_000L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(WorkManagerStrategy.UNIQUE, ExistingWorkPolicy.REPLACE, request)
        return Result.success()
    }
}
