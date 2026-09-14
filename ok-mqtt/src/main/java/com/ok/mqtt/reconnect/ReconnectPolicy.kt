package com.ok.mqtt.reconnect

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Classifies why the client disconnected.
 */
sealed class DisconnectCause {
    data class NetworkLost(val validated: Boolean) : DisconnectCause()
    data class KeepaliveTimeout(val elapsedMs: Long) : DisconnectCause()
    data class Broker(val reasonCode: Int?, val message: String?) : DisconnectCause()
    data class Transport(val error: Throwable) : DisconnectCause()
    data object AuthFailed : DisconnectCause()
    data object TlsFailed : DisconnectCause()
    data object UserRequested : DisconnectCause()
}

/**
 * Pluggable reconnect strategy. Does not touch the MQTT client directly.
 */
interface ReconnectPolicy {
    fun shouldReconnect(cause: DisconnectCause): Boolean

    /** @return delay in milliseconds, or null to stop retrying */
    fun nextDelayMs(attempt: Int, cause: DisconnectCause): Long?
}

/**
 * Exponential backoff with jitter. Non-recoverable causes never reconnect.
 */
class ExponentialBackoffPolicy(
    private val initialDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 60_000L,
    private val multiplier: Double = 2.0,
    private val jitterRatio: Double = 0.2,
    private val maxAttempts: Int = Int.MAX_VALUE,
    private val reconnectOnSessionTakenOver: Boolean = true
) : ReconnectPolicy {

    override fun shouldReconnect(cause: DisconnectCause): Boolean = when (cause) {
        is DisconnectCause.UserRequested,
        is DisconnectCause.AuthFailed,
        is DisconnectCause.TlsFailed -> false
        is DisconnectCause.Broker -> {
            val code = cause.reasonCode
            when {
                code == null -> true
                code == 0x87 || code == 0x86 || code == 0x84 || code == 0x8C -> false
                code == 0x8E -> reconnectOnSessionTakenOver
                else -> true
            }
        }
        is DisconnectCause.NetworkLost,
        is DisconnectCause.KeepaliveTimeout,
        is DisconnectCause.Transport -> true
    }

    override fun nextDelayMs(attempt: Int, cause: DisconnectCause): Long? {
        if (!shouldReconnect(cause)) return null
        if (attempt > maxAttempts) return null
        val safeAttempt = attempt.coerceAtLeast(1)
        val exp = initialDelayMs * multiplier.pow((safeAttempt - 1).toDouble())
        val capped = min(exp, maxDelayMs.toDouble())
        val jitter = capped * jitterRatio * (Random.nextDouble() * 2 - 1)
        return (capped + jitter).toLong().coerceAtLeast(0)
    }
}
