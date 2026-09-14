package com.ok.mqtt

import com.ok.mqtt.reconnect.DisconnectCause

/**
 * Observable connection lifecycle states.
 */
sealed class MqttState {
    data object Idle : MqttState()
    data object Connecting : MqttState()
    data class Connected(
        val sessionPresent: Boolean,
        val reconnect: Boolean,
        val connectedAtEpochMs: Long = System.currentTimeMillis()
    ) : MqttState()

    data class Reconnecting(
        val attempt: Int,
        val nextDelayMs: Long,
        val cause: DisconnectCause
    ) : MqttState()

    data class Failed(val cause: DisconnectCause) : MqttState()
    data object Disconnected : MqttState()
}

/**
 * Incoming publish delivered to the application.
 */
data class IncomingMessage(
    val topic: String,
    val payload: ByteArray,
    val qos: Qos,
    val retained: Boolean,
    val duplicate: Boolean = false,
    val userProperties: Map<String, String> = emptyMap(),
    val contentType: String? = null,
    val responseTopic: String? = null,
    val correlationData: ByteArray? = null,
    val messageExpiryInterval: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingMessage) return false
        return topic == other.topic &&
            payload.contentEquals(other.payload) &&
            qos == other.qos &&
            retained == other.retained &&
            duplicate == other.duplicate &&
            userProperties == other.userProperties &&
            contentType == other.contentType &&
            responseTopic == other.responseTopic &&
            correlationData.contentEquals(other.correlationData) &&
            messageExpiryInterval == other.messageExpiryInterval
    }

    override fun hashCode(): Int {
        var result = topic.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + qos.hashCode()
        result = 31 * result + retained.hashCode()
        result = 31 * result + duplicate.hashCode()
        result = 31 * result + userProperties.hashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (responseTopic?.hashCode() ?: 0)
        result = 31 * result + (correlationData?.contentHashCode() ?: 0)
        result = 31 * result + (messageExpiryInterval?.hashCode() ?: 0)
        return result
    }
}

/**
 * Thrown when an MQTT 5-only feature is used on an MQTT 3 session.
 */
class UnsupportedMqttFeatureException(feature: String) :
    IllegalStateException("Feature '$feature' requires MQTT 5.0")
