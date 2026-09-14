package com.ok.mqtt.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.ok.mqtt.reconnect.DisconnectCause
import com.ok.mqtt.reconnect.ExponentialBackoffPolicy
import com.ok.mqtt.store.OutboundBuffer
import com.ok.mqtt.store.OutboundMessage
import com.ok.mqtt.store.SubscriptionStore
import com.ok.mqtt.Qos

/**
 * Pure JVM tests for session-adjacent logic (no Android runtime).
 */
class SessionLogicTest {

    @Test
    fun subscriptionStore_roundTrip() {
        val store = SubscriptionStore()
        store.upsert("a/b", Qos.AtLeastOnce)
        store.upsert("c/#", Qos.ExactlyOnce)
        assertEquals(2, store.all().size)
        store.remove("a/b")
        assertEquals(1, store.all().size)
        assertEquals("c/#", store.all().first().topic)
    }

    @Test
    fun outboundBuffer_respectsMaxSize() {
        val buffer = OutboundBuffer(2)
        assertTrue(buffer.offer(OutboundMessage("t", byteArrayOf(1), Qos.AtMostOnce, false)))
        assertTrue(buffer.offer(OutboundMessage("t", byteArrayOf(2), Qos.AtMostOnce, false)))
        assertFalse(buffer.offer(OutboundMessage("t", byteArrayOf(3), Qos.AtMostOnce, false)))
        val drained = buffer.drain()
        assertEquals(2, drained.size)
        assertEquals(0, buffer.size())
    }

    @Test
    fun firstConnectFailure_usesSamePolicyAsReconnect() {
        val policy = ExponentialBackoffPolicy(jitterRatio = 0.0)
        val cause = DisconnectCause.Transport(RuntimeException("connection refused"))
        assertTrue(policy.shouldReconnect(cause))
        assertEquals(1000L, policy.nextDelayMs(1, cause))
    }
}
