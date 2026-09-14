# ok-mqtt

Android MQTT client library powered by [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client).

HiveMQ handles MQTT 3.1.1 / 5.0 protocol I/O. This library owns:

- Connection state machine (`OkMqttClient` / `MqttSession`)
- Pluggable reconnect policy (wired to HiveMQ `MqttClientReconnector`)
- Android network monitoring (`NetworkCallback` + VALIDATED)
- Pluggable keepalive strategies (Lifecycle / Alarm / WorkManager / Foreground Service)
- Liveness watchdog for half-open connections

## Quick start

```kotlin
val client = OkMqttClient(
    context,
    OkMqttConfig(
        serverUri = "tcp://broker.emqx.io:1883",
        clientId = "demo-1",
        version = MqttVersion.V5, // or V3_1_1
        session = SessionOptions(cleanStart = true, keepAliveSeconds = 60),
        keepalive = KeepaliveConfig(
            enableAlarm = true,
            enableLifecycle = true,
            enableForegroundService = false
        )
    )
)

lifecycleScope.launch {
    client.state.collect { /* Idle/Connecting/Connected/Reconnecting/Failed/Disconnected */ }
}
lifecycleScope.launch {
    client.incoming.collect { msg -> /* IncomingMessage */ }
}

client.connect()
client.subscribe("test/topic", Qos.AtLeastOnce)
client.publish("test/topic", "hello")
client.disconnect()
client.close()
```

## Requirements

- minSdk 24
- HiveMQ MQTT Client 1.3.17

## Module layout

- `com.ok.mqtt` — public API
- `com.ok.mqtt.session` — state machine
- `com.ok.mqtt.transport` — HiveMQ adapter
- `com.ok.mqtt.reconnect` — reconnect policy
- `com.ok.mqtt.keepalive` — Android keepalive strategies
- `com.ok.mqtt.network` — NetworkMonitor
- `com.ok.mqtt.auth` — AuthProvider
- `com.ok.mqtt.store` — subscription / outbound buffer
