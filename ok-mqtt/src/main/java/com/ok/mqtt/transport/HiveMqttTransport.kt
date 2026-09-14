package com.ok.mqtt.transport

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.lifecycle.Mqtt3ClientDisconnectedContext
import com.hivemq.client.mqtt.mqtt3.message.connect.Mqtt3Connect
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientDisconnectedContext
import com.hivemq.client.mqtt.mqtt5.message.connect.Mqtt5Connect
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.ok.mqtt.Auth
import com.ok.mqtt.IncomingMessage
import com.ok.mqtt.MqttVersion
import com.ok.mqtt.OkMqttConfig
import com.ok.mqtt.Qos
import com.ok.mqtt.UnsupportedMqttFeatureException
import com.ok.mqtt.reconnect.DisconnectCause
import com.ok.mqtt.store.Subscription
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer

/**
 * HiveMQ transport adapter for MQTT 3.1.1 / 5.0.
 * Does not enable HiveMQ's built-in automaticReconnectWithDefaultConfig;
 * reconnect is driven by [ReconnectorBridge] from disconnected listeners.
 */
class HiveMqttTransport(
    private val config: OkMqttConfig,
    private val callbacks: TransportCallbacks
) {
    interface TransportCallbacks {
        fun onConnected(sessionPresent: Boolean, reconnect: Boolean)
        fun onDisconnected(cause: DisconnectCause, source: DisconnectSource, reconnector: ReconnectorBridge)
        fun onMessage(message: IncomingMessage)
        fun onActivity()
    }

    enum class DisconnectSource { USER, SERVER, CLIENT }

    interface ReconnectorBridge {
        fun reconnect(enabled: Boolean): ReconnectorBridge
        fun delay(delayMs: Long): ReconnectorBridge
        /**
         * Must be called on HiveMQ eventLoop. [onComplete] also runs on the eventLoop
         * when the future finishes (HiveMQ schedules it there).
         */
        fun reconnectWhen(
            future: CompletableFuture<*>,
            onComplete: ((Any?, Throwable?) -> Unit)? = null
        ): ReconnectorBridge
        fun applyAuth(auth: Auth)
        val attempts: Int
    }

    data class Endpoint(
        val host: String,
        val port: Int,
        val ssl: Boolean,
        val websocket: Boolean
    )

    private val endpoint = parseEndpoint(config.serverUri)

    @Volatile
    private var client5: Mqtt5AsyncClient? = null

    @Volatile
    private var client3: Mqtt3AsyncClient? = null

    @Volatile
    private var everConnected = false

    fun connect(auth: Auth = config.auth): CompletableFuture<Unit> {
        return when (config.version) {
            MqttVersion.V5 -> connectV5(auth)
            MqttVersion.V3_1_1 -> connectV3(auth)
        }
    }

    fun disconnect(graceful: Boolean): CompletableFuture<Unit> {
        return when (config.version) {
            MqttVersion.V5 -> {
                val c = client5 ?: return CompletableFuture.completedFuture(Unit)
                c.disconnect().thenApply { }
            }
            MqttVersion.V3_1_1 -> {
                val c = client3 ?: return CompletableFuture.completedFuture(Unit)
                c.disconnect().thenApply { }
            }
        }
    }

    fun forceDisconnectForWatchdog(): CompletableFuture<Unit> = disconnect(graceful = false)

    /** Whether the underlying HiveMQ client currently reports connected. */
    fun isConnected(): Boolean {
        return when (config.version) {
            MqttVersion.V5 -> client5?.state?.isConnected == true
            MqttVersion.V3_1_1 -> client3?.state?.isConnected == true
        }
    }

    fun publish(
        topic: String,
        payload: ByteArray,
        qos: Qos,
        retained: Boolean,
        userProperties: Map<String, String> = emptyMap()
    ): CompletableFuture<Unit> {
        return when (config.version) {
            MqttVersion.V5 -> {
                val c = client5 ?: return failed("not connected")
                val builder = c.publishWith()
                    .topic(topic)
                    .payload(payload)
                    .qos(toHiveQos(qos))
                    .retain(retained)
                if (userProperties.isNotEmpty()) {
                    val up = builder.userProperties()
                    userProperties.forEach { (k, v) -> up.add(k, v) }
                    up.applyUserProperties()
                        .send()
                        .thenApply { callbacks.onActivity(); Unit }
                } else {
                    builder.send().thenApply { callbacks.onActivity(); Unit }
                }
            }
            MqttVersion.V3_1_1 -> {
                if (userProperties.isNotEmpty()) {
                    return failedFuture(UnsupportedMqttFeatureException("userProperties"))
                }
                val c = client3 ?: return failed("not connected")
                c.publishWith()
                    .topic(topic)
                    .payload(payload)
                    .qos(toHiveQos(qos))
                    .retain(retained)
                    .send()
                    .thenApply { callbacks.onActivity(); Unit }
            }
        }
    }

    fun subscribe(topic: String, qos: Qos): CompletableFuture<Unit> {
        return when (config.version) {
            MqttVersion.V5 -> {
                val c = client5 ?: return failed("not connected")
                c.subscribeWith()
                    .topicFilter(topic)
                    .qos(toHiveQos(qos))
                    .send()
                    .thenApply { Unit }
            }
            MqttVersion.V3_1_1 -> {
                val c = client3 ?: return failed("not connected")
                c.subscribeWith()
                    .topicFilter(topic)
                    .qos(toHiveQos(qos))
                    .send()
                    .thenApply { Unit }
            }
        }
    }

    fun unsubscribe(topic: String): CompletableFuture<Unit> {
        return when (config.version) {
            MqttVersion.V5 -> {
                val c = client5 ?: return failed("not connected")
                c.unsubscribeWith().topicFilter(topic).send().thenApply { Unit }
            }
            MqttVersion.V3_1_1 -> {
                val c = client3 ?: return failed("not connected")
                c.unsubscribeWith().topicFilter(topic).send().thenApply { Unit }
            }
        }
    }

    fun resubscribeAll(subscriptions: List<Subscription>): CompletableFuture<Unit> {
        if (subscriptions.isEmpty()) return CompletableFuture.completedFuture(Unit)
        var chain = CompletableFuture.completedFuture(Unit)
        subscriptions.forEach { sub ->
            chain = chain.thenCompose { subscribe(sub.topic, sub.qos) }
        }
        return chain
    }

    fun close() {
        runCatching { client5?.disconnect()?.get(3, TimeUnit.SECONDS) }
        runCatching { client3?.disconnect()?.get(3, TimeUnit.SECONDS) }
        client5 = null
        client3 = null
    }

    private fun connectV5(auth: Auth): CompletableFuture<Unit> {
        var builder = MqttClient.builder()
            .identifier(config.clientId)
            .serverHost(endpoint.host)
            .serverPort(endpoint.port)
            .addConnectedListener { ctx -> handleConnected(ctx) }
            .addDisconnectedListener { ctx -> handleDisconnected(ctx) }

        if (endpoint.ssl) {
            builder = builder.sslWithDefaultConfig()
        }
        if (endpoint.websocket) {
            builder = builder.webSocketWithDefaultConfig()
        }

        val client = builder.useMqttVersion5().buildAsync()
        client5 = client
        client3 = null

        client.publishes(MqttGlobalPublishFilter.ALL) { pub ->
            callbacks.onActivity()
            callbacks.onMessage(toIncoming(pub))
        }

        return client.connect(buildConnect5(auth)).thenApply {
            callbacks.onActivity()
            Unit
        }
    }

    private fun connectV3(auth: Auth): CompletableFuture<Unit> {
        var builder = MqttClient.builder()
            .identifier(config.clientId)
            .serverHost(endpoint.host)
            .serverPort(endpoint.port)
            .addConnectedListener { ctx -> handleConnected(ctx) }
            .addDisconnectedListener { ctx -> handleDisconnected(ctx) }

        if (endpoint.ssl) {
            builder = builder.sslWithDefaultConfig()
        }
        if (endpoint.websocket) {
            builder = builder.webSocketWithDefaultConfig()
        }

        val client = builder.useMqttVersion3().buildAsync()
        client3 = client
        client5 = null

        client.publishes(MqttGlobalPublishFilter.ALL) { pub ->
            callbacks.onActivity()
            callbacks.onMessage(
                IncomingMessage(
                    topic = pub.topic.toString(),
                    payload = pub.payloadAsBytes,
                    qos = fromHiveQos(pub.qos),
                    retained = pub.isRetain
                )
            )
        }

        return client.connect(buildConnect3(auth)).thenApply {
            callbacks.onActivity()
            Unit
        }
    }

    private fun buildConnect5(auth: Auth): Mqtt5Connect {
        val session = config.session
        var b = Mqtt5Connect.builder()
            .cleanStart(session.cleanStart)
            .keepAlive(session.keepAliveSeconds)

        session.sessionExpirySeconds?.let { b = b.sessionExpiryInterval(it) }

        when (auth) {
            is Auth.None -> Unit
            is Auth.Simple -> {
                var sa = b.simpleAuth().username(auth.username)
                auth.password?.let { sa = sa.password(it) }
                b = sa.applySimpleAuth()
            }
        }

        session.will?.let { will ->
            b = b.willPublish()
                .topic(will.topic)
                .payload(will.payload)
                .qos(toHiveQos(will.qos))
                .retain(will.retained)
                .applyWillPublish()
        }

        config.mqtt5.receiveMaximum?.let {
            b = b.restrictions().receiveMaximum(it).applyRestrictions()
        }
        if (config.mqtt5.userProperties.isNotEmpty()) {
            val up = b.userProperties()
            config.mqtt5.userProperties.forEach { (k, v) -> up.add(k, v) }
            b = up.applyUserProperties()
        }
        return b.build()
    }

    private fun buildConnect3(auth: Auth): Mqtt3Connect {
        val session = config.session
        var b = Mqtt3Connect.builder()
            .cleanSession(session.cleanStart)
            .keepAlive(session.keepAliveSeconds)

        when (auth) {
            is Auth.None -> Unit
            is Auth.Simple -> {
                var sa = b.simpleAuth().username(auth.username)
                auth.password?.let { sa = sa.password(it) }
                b = sa.applySimpleAuth()
            }
        }

        session.will?.let { will ->
            b = b.willPublish()
                .topic(will.topic)
                .payload(will.payload)
                .qos(toHiveQos(will.qos))
                .retain(will.retained)
                .applyWillPublish()
        }
        return b.build()
    }

    private fun handleConnected(ctx: MqttClientConnectedContext) {
        val reconnect = everConnected
        everConnected = true
        callbacks.onConnected(sessionPresent = reconnect, reconnect = reconnect)
    }

    private fun handleDisconnected(ctx: MqttClientDisconnectedContext) {
        val cause = mapCause(ctx)
        val source = when (ctx.source) {
            MqttDisconnectSource.USER -> DisconnectSource.USER
            MqttDisconnectSource.SERVER -> DisconnectSource.SERVER
            else -> DisconnectSource.CLIENT
        }
        val bridge = object : ReconnectorBridge {
            private val r = ctx.reconnector
            override val attempts: Int get() = r.attempts

            override fun reconnect(enabled: Boolean): ReconnectorBridge {
                r.reconnect(enabled)
                return this
            }

            override fun delay(delayMs: Long): ReconnectorBridge {
                r.delay(delayMs, TimeUnit.MILLISECONDS)
                return this
            }

            override fun reconnectWhen(
                future: CompletableFuture<*>,
                onComplete: ((Any?, Throwable?) -> Unit)?
            ): ReconnectorBridge {
                @Suppress("UNCHECKED_CAST")
                val typed = future as CompletableFuture<Any>
                r.reconnectWhen(typed, BiConsumer { result, error ->
                    onComplete?.invoke(result, error)
                })
                return this
            }

            override fun applyAuth(auth: Auth) {
                when (config.version) {
                    MqttVersion.V5 -> {
                        val ctx5 = ctx as? Mqtt5ClientDisconnectedContext ?: return
                        when (auth) {
                            is Auth.None -> Unit
                            is Auth.Simple -> {
                                var connect = ctx5.reconnector.connectWith()
                                var sa = connect.simpleAuth().username(auth.username)
                                auth.password?.let { sa = sa.password(it) }
                                connect = sa.applySimpleAuth()
                                connect.applyConnect()
                            }
                        }
                    }
                    MqttVersion.V3_1_1 -> {
                        val ctx3 = ctx as? Mqtt3ClientDisconnectedContext ?: return
                        when (auth) {
                            is Auth.None -> Unit
                            is Auth.Simple -> {
                                var connect = ctx3.reconnector.connectWith()
                                var sa = connect.simpleAuth().username(auth.username)
                                auth.password?.let { sa = sa.password(it) }
                                connect = sa.applySimpleAuth()
                                connect.applyConnect()
                            }
                        }
                    }
                }
            }
        }
        callbacks.onDisconnected(cause, source, bridge)
    }

    private fun mapCause(ctx: MqttClientDisconnectedContext): DisconnectCause {
        if (ctx.source == MqttDisconnectSource.USER) {
            return DisconnectCause.UserRequested
        }
        val cause = ctx.cause
        val message = cause?.message?.lowercase().orEmpty()
        return when {
            message.contains("not authorized") ||
                message.contains("bad user") ||
                message.contains("authentication") -> DisconnectCause.AuthFailed
            message.contains("ssl") ||
                message.contains("tls") ||
                message.contains("certificate") -> DisconnectCause.TlsFailed
            cause != null -> DisconnectCause.Transport(cause)
            else -> DisconnectCause.Broker(null, null)
        }
    }

    private fun toIncoming(pub: Mqtt5Publish): IncomingMessage {
        val userProps = mutableMapOf<String, String>()
        pub.userProperties.asList().forEach {
            userProps[it.name.toString()] = it.value.toString()
        }
        return IncomingMessage(
            topic = pub.topic.toString(),
            payload = pub.payloadAsBytes,
            qos = fromHiveQos(pub.qos),
            retained = pub.isRetain,
            duplicate = false,
            userProperties = userProps,
            contentType = if (pub.contentType.isPresent) pub.contentType.get().toString() else null,
            responseTopic = if (pub.responseTopic.isPresent) pub.responseTopic.get().toString() else null,
            correlationData = if (pub.correlationData.isPresent) {
                bufferToBytes(pub.correlationData.get())
            } else null,
            messageExpiryInterval = if (pub.messageExpiryInterval.isPresent) {
                pub.messageExpiryInterval.asLong
            } else null
        )
    }

    private fun bufferToBytes(buf: ByteBuffer): ByteArray {
        val dup = buf.duplicate()
        val arr = ByteArray(dup.remaining())
        dup.get(arr)
        return arr
    }

    private fun toHiveQos(qos: Qos): MqttQos = when (qos) {
        Qos.AtMostOnce -> MqttQos.AT_MOST_ONCE
        Qos.AtLeastOnce -> MqttQos.AT_LEAST_ONCE
        Qos.ExactlyOnce -> MqttQos.EXACTLY_ONCE
    }

    private fun fromHiveQos(qos: MqttQos): Qos = when (qos) {
        MqttQos.AT_MOST_ONCE -> Qos.AtMostOnce
        MqttQos.AT_LEAST_ONCE -> Qos.AtLeastOnce
        MqttQos.EXACTLY_ONCE -> Qos.ExactlyOnce
    }

    private fun failed(msg: String): CompletableFuture<Unit> =
        failedFuture(IllegalStateException(msg))

    private fun <T> failedFuture(error: Throwable): CompletableFuture<T> {
        val f = CompletableFuture<T>()
        f.completeExceptionally(error)
        return f
    }

    companion object {
        fun parseEndpoint(serverUri: String): Endpoint {
            val normalized = when {
                serverUri.contains("://") -> serverUri
                else -> "tcp://$serverUri"
            }
            val uri = URI(normalized)
            val scheme = uri.scheme?.lowercase() ?: "tcp"
            val host = uri.host ?: uri.authority?.substringBefore(':')
                ?: throw IllegalArgumentException("Invalid serverUri: $serverUri")
            val ssl = scheme == "ssl" || scheme == "tls" || scheme == "mqtts" || scheme == "wss"
            val websocket = scheme == "ws" || scheme == "wss"
            val defaultPort = when {
                websocket && ssl -> 443
                websocket -> 80
                ssl -> 8883
                else -> 1883
            }
            val port = if (uri.port > 0) uri.port else defaultPort
            return Endpoint(host, port, ssl, websocket)
        }
    }
}
