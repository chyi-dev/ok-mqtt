package com.ok.mqtt.keepalive

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Configuration for pluggable keepalive strategies.
 */
data class KeepaliveConfig(
    val foregroundKeepAliveSeconds: Int = 60,
    val backgroundKeepAliveSeconds: Int = 60,
    val watchdogMultiplier: Double = 1.5,
    val enableForegroundService: Boolean = false,
    val enableAlarm: Boolean = true,
    val enableWorkManager: Boolean = false,
    val enableLifecycle: Boolean = true,
    val notificationTitle: String = "MQTT connected",
    val notificationText: String = "Keeping MQTT connection alive",
    val notificationChannelId: String = "ok_mqtt_keepalive",
    val notificationId: Int = 0x4D5154 // "MQT"
) {
    companion object {
        val Default = KeepaliveConfig()
    }
}

/**
 * Handle passed to strategies so they can wake the engine / request a liveness check.
 */
interface KeepaliveHandle {
    fun onWakeRequested()
    fun onLivenessCheckRequested()
    fun keepAliveIntervalMs(): Long
}

/**
 * Pluggable Android keepalive strategy. Does NOT send MQTT PINGREQ itself.
 */
interface KeepaliveStrategy {
    fun start(handle: KeepaliveHandle)
    fun stop()
    fun onForeground() {}
    fun onBackground() {}
}

/**
 * Combines multiple [KeepaliveStrategy] instances.
 */
class KeepaliveCoordinator(
    private val strategies: List<KeepaliveStrategy>
) : KeepaliveStrategy {

    private val active = CopyOnWriteArrayList<KeepaliveStrategy>()

    override fun start(handle: KeepaliveHandle) {
        stop()
        strategies.forEach { strategy ->
            strategy.start(handle)
            active.add(strategy)
        }
    }

    override fun stop() {
        active.forEach { runCatching { it.stop() } }
        active.clear()
    }

    override fun onForeground() {
        active.forEach { it.onForeground() }
    }

    override fun onBackground() {
        active.forEach { it.onBackground() }
    }

    fun addStrategy(strategy: KeepaliveStrategy, handle: KeepaliveHandle?) {
        strategy.let {
            if (handle != null) it.start(handle)
            active.add(it)
        }
    }
}

/**
 * Tracks last MQTT activity and signals keepalive timeout.
 */
class LivenessWatchdog(
    private val timeoutMultiplier: Double = 1.5,
    private val onTimeout: (elapsedMs: Long) -> Unit
) {
    @Volatile
    private var lastActivityMs: Long = System.currentTimeMillis()

    @Volatile
    private var keepAliveMs: Long = 60_000L

    @Volatile
    private var running = false

    fun start(keepAliveSeconds: Int) {
        keepAliveMs = keepAliveSeconds.coerceAtLeast(1) * 1000L
        lastActivityMs = System.currentTimeMillis()
        running = true
    }

    fun stop() {
        running = false
    }

    fun markActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    fun updateKeepAliveSeconds(seconds: Int) {
        keepAliveMs = seconds.coerceAtLeast(1) * 1000L
    }

    fun checkNow() {
        if (!running) return
        val elapsed = System.currentTimeMillis() - lastActivityMs
        val limit = (keepAliveMs * timeoutMultiplier).toLong()
        if (elapsed > limit) {
            onTimeout(elapsed)
        }
    }

    fun elapsedMs(): Long = System.currentTimeMillis() - lastActivityMs
}
