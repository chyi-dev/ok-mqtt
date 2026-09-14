package com.ok.mqtt

import android.content.Context
import com.ok.mqtt.session.MqttSession
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public entry point for ok-mqtt.
 *
 * HiveMQ is used only as the protocol engine; reconnect policy, Android keepalive,
 * and network monitoring are owned by this library.
 */
class OkMqttClient(
    context: Context,
    val config: OkMqttConfig
) {
    private val session = MqttSession(context.applicationContext, config)

    val state: StateFlow<MqttState> = session.state
    val incoming: SharedFlow<IncomingMessage> = session.incoming

    fun connect() = session.connect()

    fun disconnect(graceful: Boolean = true) = session.disconnect(graceful)

    fun publish(
        topic: String,
        payload: ByteArray,
        qos: Qos = Qos.AtLeastOnce,
        retained: Boolean = false,
        userProperties: Map<String, String> = emptyMap()
    ) = session.publish(topic, payload, qos, retained, userProperties)

    fun publish(
        topic: String,
        payload: String,
        qos: Qos = Qos.AtLeastOnce,
        retained: Boolean = false
    ) = publish(topic, payload.toByteArray(Charsets.UTF_8), qos, retained)

    fun subscribe(topic: String, qos: Qos = Qos.AtLeastOnce) = session.subscribe(topic, qos)

    fun unsubscribe(topic: String) = session.unsubscribe(topic)

    fun close() = session.close()
}
