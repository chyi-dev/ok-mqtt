package com.ok.mqtt.session

import android.content.Context
import android.util.Log
import com.ok.mqtt.Auth
import com.ok.mqtt.IncomingMessage
import com.ok.mqtt.MqttState
import com.ok.mqtt.OkMqttConfig
import com.ok.mqtt.Qos
import com.ok.mqtt.keepalive.AlarmPingStrategy
import com.ok.mqtt.keepalive.ForegroundServiceStrategy
import com.ok.mqtt.keepalive.KeepaliveCoordinator
import com.ok.mqtt.keepalive.KeepaliveHandle
import com.ok.mqtt.keepalive.LifecycleStrategy
import com.ok.mqtt.keepalive.LivenessWatchdog
import com.ok.mqtt.keepalive.WorkManagerStrategy
import com.ok.mqtt.network.NetworkMonitor
import com.ok.mqtt.reconnect.DisconnectCause
import com.ok.mqtt.store.OutboundBuffer
import com.ok.mqtt.store.OutboundMessage
import com.ok.mqtt.store.SubscriptionStore
import com.ok.mqtt.transport.HiveMqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single owner of MQTT connection lifecycle.
 */
class MqttSession(
    context: Context,
    private val config: OkMqttConfig
) {
    companion object {
        private const val TAG = "MqttSession"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<MqttState>(MqttState.Idle)
    val state: StateFlow<MqttState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

    private val networkMonitor = NetworkMonitor(appContext)
    private val subscriptionStore = SubscriptionStore()
    private val outboundBuffer = OutboundBuffer(config.offlineBufferSize)

    private val userDisconnect = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    /** Set before watchdog calls client.disconnect(); HiveMQ reports that as USER. */
    private val watchdogForcedDisconnect = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    private val foreground = AtomicBoolean(true)

    private var transport: HiveMqttTransport? = null
    private var keepalive: KeepaliveCoordinator? = null
    private var watchdog: LivenessWatchdog? = null
    private var currentAuth: Auth = config.auth

    private val networkListener: (Boolean) -> Unit = { validated ->
        if (validated) {
            Log.d(TAG, "Network validated")
        } else {
            Log.d(TAG, "Network lost / not validated")
            val current = _state.value
            if (current is MqttState.Connected) {
                // Soft mark — HiveMQ may still discover; if not, watchdog will force
            }
        }
    }

    init {
        networkMonitor.start()
        networkMonitor.addListener(networkListener)
        currentAuth = config.auth
    }

    fun connect() {
        if (closed.get()) return
        scope.launch {
            mutex.withLock {
                if (_state.value is MqttState.Connecting || _state.value is MqttState.Connected) {
                    return@withLock
                }
                userDisconnect.set(false)
                _state.value = MqttState.Connecting
                ensureTransport()
                doConnect()
            }
        }
    }

    fun disconnect(graceful: Boolean = true) {
        scope.launch {
            mutex.withLock {
                userDisconnect.set(true)
                stopKeepalive()
                transport?.disconnect(graceful)?.whenComplete { _, _ ->
                    _state.value = MqttState.Disconnected
                } ?: run {
                    _state.value = MqttState.Disconnected
                }
            }
        }
    }

    fun publish(
        topic: String,
        payload: ByteArray,
        qos: Qos = Qos.AtLeastOnce,
        retained: Boolean = false,
        userProperties: Map<String, String> = emptyMap()
    ) {
        scope.launch {
            val t = transport
            if (t == null || _state.value !is MqttState.Connected) {
                val ok = outboundBuffer.offer(
                    OutboundMessage(topic, payload, qos, retained, userProperties)
                )
                if (!ok) Log.w(TAG, "Outbound buffer full, drop publish to $topic")
                return@launch
            }
            t.publish(topic, payload, qos, retained, userProperties)
                .whenComplete { _, err ->
                    if (err != null) {
                        Log.w(TAG, "Publish failed: ${err.message}")
                        outboundBuffer.offer(
                            OutboundMessage(topic, payload, qos, retained, userProperties)
                        )
                    }
                }
        }
    }

    fun subscribe(topic: String, qos: Qos) {
        scope.launch {
            subscriptionStore.upsert(topic, qos)
            val t = transport
            if (t != null && _state.value is MqttState.Connected) {
                t.subscribe(topic, qos)
            }
        }
    }

    fun unsubscribe(topic: String) {
        scope.launch {
            subscriptionStore.remove(topic)
            val t = transport
            if (t != null && _state.value is MqttState.Connected) {
                t.unsubscribe(topic)
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        userDisconnect.set(true)
        stopKeepalive()
        networkMonitor.removeListener(networkListener)
        networkMonitor.stop()
        transport?.close()
        transport = null
        scope.cancel()
        _state.value = MqttState.Disconnected
    }

    private fun ensureTransport() {
        if (transport != null) return
        transport = HiveMqttTransport(config, object : HiveMqttTransport.TransportCallbacks {
            override fun onConnected(sessionPresent: Boolean, reconnect: Boolean) {
                scope.launch {
                    mutex.withLock {
                        reconnectAttempt.set(0)
                        _state.value = MqttState.Connected(sessionPresent, reconnect)
                        startKeepalive()
                        restoreSubscriptions(sessionPresent)
                        flushOutbound()
                    }
                }
            }

            override fun onDisconnected(
                cause: DisconnectCause,
                source: HiveMqttTransport.DisconnectSource,
                reconnector: HiveMqttTransport.ReconnectorBridge
            ) {
                // HiveMQ requires MqttClientReconnector calls on its eventLoop.
                // Do NOT hop to another dispatcher before touching reconnector.
                handleDisconnect(cause, source, reconnector)
                scope.launch { stopKeepalive() }
            }

            override fun onMessage(message: IncomingMessage) {
                watchdog?.markActivity()
                scope.launch { _incoming.emit(message) }
            }

            override fun onActivity() {
                watchdog?.markActivity()
            }
        })
    }

    private fun doConnect() {
        val t = transport ?: return
        val authFuture = config.authProvider?.refresh()
        if (authFuture != null) {
            authFuture.whenComplete { auth, err ->
                scope.launch {
                    mutex.withLock {
                        if (err != null) {
                            _state.value = MqttState.Failed(DisconnectCause.AuthFailed)
                            return@withLock
                        }
                        currentAuth = auth
                        startConnect(t, auth)
                    }
                }
            }
        } else {
            startConnect(t, currentAuth)
        }
    }

    private fun startConnect(t: HiveMqttTransport, auth: Auth) {
        t.connect(auth).whenComplete { _, err ->
            scope.launch {
                mutex.withLock {
                    if (err != null && _state.value is MqttState.Connecting) {
                        // Initial connect failure is also delivered via disconnected listener
                        // for HiveMQ; this is a safety net.
                        Log.w(TAG, "Connect failed: ${err.message}")
                        val cause = classifyThrowable(err)
                        if (!config.reconnect.shouldReconnect(cause)) {
                            _state.value = MqttState.Failed(cause)
                        }
                        // If reconnectable, disconnected listener / manual retry path handles it.
                        // For first connect without client yet built, schedule manual retry:
                        if (config.reconnect.shouldReconnect(cause) && !userDisconnect.get()) {
                            scheduleManualRetry(cause)
                        }
                    }
                }
            }
        }
    }

    private fun scheduleManualRetry(cause: DisconnectCause) {
        val attempt = reconnectAttempt.incrementAndGet()
        val delayMs = config.reconnect.nextDelayMs(attempt, cause) ?: run {
            _state.value = MqttState.Failed(cause)
            return
        }
        _state.value = MqttState.Reconnecting(attempt, delayMs, cause)
        scope.launch {
            if (!networkMonitor.isValidated()) {
                networkMonitor.awaitValidated().join()
            }
            kotlinx.coroutines.delay(delayMs)
            mutex.withLock {
                if (userDisconnect.get() || closed.get()) return@withLock
                if (_state.value !is MqttState.Reconnecting) return@withLock
                _state.value = MqttState.Connecting
                doConnect()
            }
        }
    }

    private fun handleDisconnect(
        cause: DisconnectCause,
        source: HiveMqttTransport.DisconnectSource,
        reconnector: HiveMqttTransport.ReconnectorBridge
    ) {
        val watchdogForced = watchdogForcedDisconnect.getAndSet(false)
        val effectiveCause = when {
            // Client.disconnect() from watchdog is reported as USER by HiveMQ — remap it.
            watchdogForced -> DisconnectCause.KeepaliveTimeout(
                watchdog?.elapsedMs() ?: (config.session.keepAliveSeconds * 1000L)
            )
            userDisconnect.get() -> DisconnectCause.UserRequested
            source == HiveMqttTransport.DisconnectSource.USER ->
                // Unexpected client-side disconnect without userDisconnect flag
                DisconnectCause.Transport(
                    IllegalStateException("Client disconnect without user request")
                )
            else -> cause
        }

        if (effectiveCause is DisconnectCause.UserRequested) {
            reconnector.reconnect(false)
            _state.value = MqttState.Disconnected
            return
        }

        if (!config.reconnect.shouldReconnect(effectiveCause)) {
            reconnector.reconnect(false)
            _state.value = MqttState.Failed(effectiveCause)
            return
        }

        val attempt = maxOf(reconnector.attempts, reconnectAttempt.incrementAndGet())
        reconnectAttempt.set(attempt)
        val delayMs = config.reconnect.nextDelayMs(attempt, effectiveCause) ?: run {
            reconnector.reconnect(false)
            _state.value = MqttState.Failed(effectiveCause)
            return
        }

        _state.value = MqttState.Reconnecting(attempt, delayMs, effectiveCause)
        reconnector.reconnect(true).delay(delayMs)

        if (!networkMonitor.isValidated()) {
            reconnector.reconnectWhen(networkMonitor.awaitValidated())
        }

        val provider = config.authProvider
        if (provider != null) {
            reconnector.reconnectWhen(provider.refresh()) { result, err ->
                if (err == null && result is Auth) {
                    currentAuth = result
                    reconnector.applyAuth(result)
                }
            }
        }
    }

    private fun restoreSubscriptions(sessionPresent: Boolean) {
        if (!config.autoResubscribe) return
        val needRestore = config.session.cleanStart || !sessionPresent
        if (!needRestore && subscriptionStore.all().isEmpty()) return
        val subs = subscriptionStore.all()
        if (subs.isEmpty()) return
        transport?.resubscribeAll(subs)?.whenComplete { _, err ->
            if (err != null) Log.w(TAG, "Resubscribe failed: ${err.message}")
        }
    }

    private fun flushOutbound() {
        val pending = outboundBuffer.drain()
        val t = transport ?: return
        pending.forEach { msg ->
            t.publish(msg.topic, msg.payload, msg.qos, msg.retained, msg.userProperties)
        }
    }

    private fun startKeepalive() {
        stopKeepalive()
        val cfg = config.keepalive
        watchdog = LivenessWatchdog(cfg.watchdogMultiplier) { elapsed ->
            Log.w(TAG, "Liveness watchdog timeout after ${elapsed}ms")
            scope.launch {
                mutex.withLock {
                    if (userDisconnect.get() || closed.get()) return@withLock
                    if (_state.value !is MqttState.Connected) return@withLock
                    watchdogForcedDisconnect.set(true)
                    transport?.forceDisconnectForWatchdog()
                }
            }
        }.also {
            it.start(config.session.keepAliveSeconds)
        }

        val strategies = buildList {
            if (cfg.enableLifecycle) {
                add(LifecycleStrategy { isFg ->
                    foreground.set(isFg)
                    val seconds = if (isFg) cfg.foregroundKeepAliveSeconds else cfg.backgroundKeepAliveSeconds
                    watchdog?.updateKeepAliveSeconds(seconds)
                    // Foreground return: give Netty a moment, then probe
                    scope.launch {
                        kotlinx.coroutines.delay(1_000)
                        probeLivenessAfterWake()
                    }
                })
            }
            if (cfg.enableAlarm) add(AlarmPingStrategy(appContext))
            if (cfg.enableWorkManager) add(WorkManagerStrategy(appContext))
            if (cfg.enableForegroundService) add(ForegroundServiceStrategy(appContext, cfg))
        }
        val coordinator = KeepaliveCoordinator(strategies)
        keepalive = coordinator
        val handle = object : KeepaliveHandle {
            override fun onWakeRequested() {
                Log.d(TAG, "Keepalive wake")
                // After CPU wake, let HiveMQ Netty run PING before judging liveness.
                scope.launch {
                    kotlinx.coroutines.delay(3_000)
                    probeLivenessAfterWake()
                }
            }

            override fun onLivenessCheckRequested() {
                // Immediate checks (e.g. lifecycle) still go through probe, not raw timeout
                // on unobservable PING activity alone.
                scope.launch { probeLivenessAfterWake() }
            }

            override fun keepAliveIntervalMs(): Long {
                val seconds = if (foreground.get()) {
                    cfg.foregroundKeepAliveSeconds
                } else {
                    cfg.backgroundKeepAliveSeconds
                }
                return seconds.coerceAtLeast(5) * 1000L
            }
        }
        coordinator.start(handle)
    }

    /**
     * HiveMQ protocol PING does not surface through our callbacks, so idle-but-healthy
     * connections look "dead" to a pure activity timer.
     *
     * After a wake: if the client still reports connected, refresh activity (HiveMQ
     * keepalive can run once the CPU is awake). If not connected, force reconnect.
     * Half-open sockets that still report connected are left for HiveMQ's own
     * keepalive to fail after the wake.
     */
    private fun probeLivenessAfterWake() {
        if (userDisconnect.get() || closed.get()) return
        if (_state.value !is MqttState.Connected) return
        val t = transport ?: return
        if (t.isConnected()) {
            watchdog?.markActivity()
            Log.d(TAG, "Liveness probe: still connected, activity refreshed")
        } else {
            Log.w(TAG, "Liveness probe: transport not connected, forcing reconnect")
            val elapsed = watchdog?.elapsedMs() ?: 0L
            watchdogForcedDisconnect.set(true)
            // Prefer going through watchdog callback so logs stay consistent
            Log.w(TAG, "Liveness watchdog timeout after ${elapsed}ms")
            t.forceDisconnectForWatchdog()
        }
    }

    private fun stopKeepalive() {
        keepalive?.stop()
        keepalive = null
        watchdog?.stop()
        watchdog = null
    }

    private fun classifyThrowable(err: Throwable): DisconnectCause {
        val msg = err.message?.lowercase().orEmpty()
        return when {
            msg.contains("not authorized") || msg.contains("bad user") || msg.contains("auth") ->
                DisconnectCause.AuthFailed
            msg.contains("ssl") || msg.contains("tls") || msg.contains("certificate") ->
                DisconnectCause.TlsFailed
            else -> DisconnectCause.Transport(err)
        }
    }
}
