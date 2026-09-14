package com.ok.mqtt

import com.ok.mqtt.auth.AuthProvider
import com.ok.mqtt.keepalive.KeepaliveConfig
import com.ok.mqtt.reconnect.ExponentialBackoffPolicy
import com.ok.mqtt.reconnect.ReconnectPolicy

/**
 * Public configuration for [OkMqttClient].
 */
data class OkMqttConfig(
    val serverUri: String,
    val clientId: String,
    val version: MqttVersion = MqttVersion.V5,
    val auth: Auth = Auth.None,
    val authProvider: AuthProvider? = null,
    val session: SessionOptions = SessionOptions(),
    val reconnect: ReconnectPolicy = ExponentialBackoffPolicy(),
    val keepalive: KeepaliveConfig = KeepaliveConfig.Default,
    val mqtt5: Mqtt5Options = Mqtt5Options(),
    val offlineBufferSize: Int = 10_000,
    val autoResubscribe: Boolean = true
)

/**
 * Authentication credentials used at connect time.
 */
sealed class Auth {
    data object None : Auth()
    data class Simple(val username: String, val password: ByteArray?) : Auth() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Simple) return false
            return username == other.username && password.contentEquals(other.password)
        }

        override fun hashCode(): Int {
            var result = username.hashCode()
            result = 31 * result + (password?.contentHashCode() ?: 0)
            return result
        }
    }
}

/**
 * Session / keep-alive options shared by MQTT 3 and 5.
 *
 * - [cleanStart] maps to MQTT 3 `cleanSession`
 * - [sessionExpirySeconds] is MQTT 5 only; ignored on v3
 */
data class SessionOptions(
    val cleanStart: Boolean = true,
    val sessionExpirySeconds: Long? = null,
    val keepAliveSeconds: Int = 60,
    val will: WillMessage? = null
)

data class WillMessage(
    val topic: String,
    val payload: ByteArray,
    val qos: Qos = Qos.AtLeastOnce,
    val retained: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WillMessage) return false
        return topic == other.topic &&
            payload.contentEquals(other.payload) &&
            qos == other.qos &&
            retained == other.retained
    }

    override fun hashCode(): Int {
        var result = topic.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + qos.hashCode()
        result = 31 * result + retained.hashCode()
        return result
    }
}

/**
 * MQTT 5-only connect options. Ignored when [OkMqttConfig.version] is [MqttVersion.V3_1_1].
 */
data class Mqtt5Options(
    val receiveMaximum: Int? = null,
    val maximumPacketSize: Long? = null,
    val topicAliasMaximum: Int? = null,
    val requestResponseInfo: Boolean = false,
    val requestProblemInfo: Boolean = true,
    val userProperties: Map<String, String> = emptyMap()
)
