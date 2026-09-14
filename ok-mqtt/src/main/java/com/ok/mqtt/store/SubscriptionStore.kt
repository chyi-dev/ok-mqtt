package com.ok.mqtt.store

import com.ok.mqtt.Qos
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class Subscription(
    val topic: String,
    val qos: Qos
)

/**
 * In-memory snapshot of active subscriptions for reconnect / clean session restore.
 */
class SubscriptionStore {
    private val map = ConcurrentHashMap<String, Subscription>()

    fun upsert(topic: String, qos: Qos) {
        map[topic] = Subscription(topic, qos)
    }

    fun remove(topic: String) {
        map.remove(topic)
    }

    fun all(): List<Subscription> = map.values.toList()

    fun clear() {
        map.clear()
    }
}

data class OutboundMessage(
    val topic: String,
    val payload: ByteArray,
    val qos: Qos,
    val retained: Boolean,
    val userProperties: Map<String, String> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboundMessage) return false
        return topic == other.topic &&
            payload.contentEquals(other.payload) &&
            qos == other.qos &&
            retained == other.retained &&
            userProperties == other.userProperties
    }

    override fun hashCode(): Int {
        var result = topic.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + qos.hashCode()
        result = 31 * result + retained.hashCode()
        result = 31 * result + userProperties.hashCode()
        return result
    }
}

/**
 * Application-level offline publish buffer (in addition to HiveMQ's own queue).
 */
class OutboundBuffer(private val maxSize: Int) {
    private val queue = CopyOnWriteArrayList<OutboundMessage>()

    fun offer(message: OutboundMessage): Boolean {
        if (queue.size >= maxSize) return false
        queue.add(message)
        return true
    }

    fun drain(): List<OutboundMessage> {
        val copy = queue.toList()
        queue.clear()
        return copy
    }

    fun size(): Int = queue.size

    fun clear() {
        queue.clear()
    }
}
