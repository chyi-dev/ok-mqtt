package com.ok.mqtt.reconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExponentialBackoffPolicyTest {

    private val policy = ExponentialBackoffPolicy(
        initialDelayMs = 1_000L,
        maxDelayMs = 60_000L,
        jitterRatio = 0.0
    )

    @Test
    fun authFailed_shouldNotReconnect() {
        assertFalse(policy.shouldReconnect(DisconnectCause.AuthFailed))
        assertNull(policy.nextDelayMs(1, DisconnectCause.AuthFailed))
    }

    @Test
    fun userRequested_shouldNotReconnect() {
        assertFalse(policy.shouldReconnect(DisconnectCause.UserRequested))
    }

    @Test
    fun tlsFailed_shouldNotReconnect() {
        assertFalse(policy.shouldReconnect(DisconnectCause.TlsFailed))
    }

    @Test
    fun networkLost_shouldReconnect_withBackoff() {
        assertTrue(policy.shouldReconnect(DisconnectCause.NetworkLost(false)))
        val d1 = policy.nextDelayMs(1, DisconnectCause.NetworkLost(true))
        val d2 = policy.nextDelayMs(2, DisconnectCause.NetworkLost(true))
        assertNotNull(d1)
        assertNotNull(d2)
        assertEquals(1000L, d1!!)
        assertEquals(2000L, d2!!)
    }

    @Test
    fun keepaliveTimeout_shouldReconnect() {
        assertTrue(policy.shouldReconnect(DisconnectCause.KeepaliveTimeout(90_000)))
    }

    @Test
    fun brokerNotAuthorized_shouldNotReconnect() {
        assertFalse(policy.shouldReconnect(DisconnectCause.Broker(0x87, "Not authorized")))
    }

    @Test
    fun delay_capsAtMax() {
        val d = policy.nextDelayMs(20, DisconnectCause.Transport(RuntimeException("x")))
        assertNotNull(d)
        assertEquals(60_000L, d!!)
    }
}
