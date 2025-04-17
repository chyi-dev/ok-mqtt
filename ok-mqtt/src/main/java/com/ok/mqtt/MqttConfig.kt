package com.ok.mqtt

import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import java.security.KeyStore
import java.security.cert.Certificate
import javax.net.ssl.SSLSocketFactory

/**
 * MQTT配置信息
 * @author Leyi
 * @date 2025/2/25 15:02
 */
class MqttConfig private constructor() {
    // 连接参数
    internal var serverUri: String = ""
    internal var clientId: String = ""
    private var keepAliveInterval: Int = 60
    private var connectionTimeout: Int = 30
    private var maxReconnectDelay: Int = 128000
    internal var isCleanSession: Boolean = false
    private var username: String? = null
    private var password: CharArray? = null
    private var maxInflight: Int = 10
    private var automaticReconnect: Boolean = false

    // SSL配置
    private var sslConfig: SSLConfig? = null

    // 遗嘱消息
    private var lastWill: LastWill? = null

    fun serverUri(uri: String) = apply {
        require(uri.isNotBlank()) { "服务器地址不能为空" }
        require(uri.matches(Regex("^(tcp|ssl|ws|wss)://.*"))) { "协议头必须为 tcp://, ssl://, ws:// 或 wss://" }
        this.serverUri = uri
    }

    fun clientId(clientId: String) = apply {
        require(clientId.isNotBlank()) { "clientId不能为空" }
        this.clientId = clientId
    }

    fun connectionTimeout(seconds: Int) = apply {
        require(seconds >= 0) { "连接超时时间不能小于0" }
        this.connectionTimeout = seconds
    }

    fun maxReconnectDelay(millis: Int) = apply {
        require(millis >= 0) { "最大重连间隔时间不能小于0" }
        this.maxReconnectDelay = millis
    }

    fun keepAliveInterval(seconds: Int) = apply {
        require(seconds >= 0) { "保活间隔不能小于0" }
        this.keepAliveInterval = seconds
    }

    fun isCleanSession(isCleanSession: Boolean) = apply {
        this.isCleanSession = isCleanSession
    }

    fun credentials(user: String, pass: String) = apply {
        require(user.isNotBlank() && pass.isNotBlank()) { "用户名和密码必须同时存在" }
        this.username = user
        this.password = pass.toCharArray()
    }

    // 配置SSL
    fun sslConfig(block: SSLConfig.() -> Unit) = apply {
        require(serverUri.startsWith("ssl://")) { "SSL配置需要服务器地址以ssl://开头" }
        sslConfig = SSLConfig().apply(block).also {
            it.validate()
        }
    }

    // 配置遗嘱消息
    fun lastWill(block: LastWill.() -> Unit) = apply {
        lastWill = LastWill().apply(block).also {
            it.validate()
        }
    }

    fun validate() {
        require(serverUri.isNotBlank()) { "服务器地址不能为空" }
        require(serverUri.matches(Regex("^(tcp|ssl|ws|wss)://.*"))) { "协议头必须为 tcp://, ssl://, ws:// 或 wss://" }
        require(clientId.isNotBlank()) { "clientId不能为空" }
        require(username != null && username!!.isNotBlank()) { "用户名不能为空" }
        require(password != null && password!!.isNotEmpty()) { "密码不能为空" }
    }

    fun toConnectOptions(): MqttConnectOptions {
        validate()
        return MqttConnectOptions().apply {
            serverURIs = arrayOf(serverUri)
            keepAliveInterval = this@MqttConfig.keepAliveInterval
            connectionTimeout = this@MqttConfig.connectionTimeout
            isCleanSession = this@MqttConfig.isCleanSession
            userName = this@MqttConfig.username
            password = this@MqttConfig.password
            maxInflight = this@MqttConfig.maxInflight
            isAutomaticReconnect = this@MqttConfig.automaticReconnect
            maxReconnectDelay = this@MqttConfig.maxReconnectDelay

            sslConfig?.let {
                socketFactory = it.createSocketFactory()
            }

            lastWill?.let {
                setWill(it.topic, it.payload, it.qos, it.retained)
            }
        }
    }


    companion object {
        fun create(block: MqttConfig.() -> Unit): MqttConfig {
            return MqttConfig().apply(block).also {
                it.validate()
            }
        }
    }

    // SSL配置构建器
    class SSLConfig {
        private var keyStore: KeyStore? = null
        private var clientCertAlias: String? = null
        private var keyPassword: String? = null
        private var caCert: Certificate? = null

        fun keyStore(store: KeyStore) = apply {
            requireNotNull(store) { "密钥库不能为空" }
            keyStore = store
        }

        fun clientCertAlias(alias: String) = apply {
            require(alias.isNotBlank()) { "客户端证书别名不能为空" }
            clientCertAlias = alias
        }

        fun keyPassword(password: String) = apply {
            keyPassword = password
        }

        fun caCertificate(cert: Certificate) = apply {
            caCert = cert
        }

        fun validate() {
            requireNotNull(keyStore) { "必须配置密钥库" }
            requireNotNull(clientCertAlias) { "必须设置客户端证书别名" }
        }

        fun createSocketFactory(): SSLSocketFactory {
            validate()
            return SSLUtils.createSocketFactory(
                keyStore = keyStore!!,
                clientCertAlias = clientCertAlias!!,
                keyPassword = keyPassword,
                caCert = caCert
            )
        }
    }

    // 遗嘱消息构建器
    class LastWill {
        var topic: String? = null
        var payload: ByteArray = byteArrayOf()
        var qos: Int = 0
        var retained: Boolean = false

        fun topic(t: String) = apply {
            require(t.isNotBlank()) { "遗嘱主题不能为空" }
            require(!t.contains("#") && !t.contains("+")) { "主题不能包含通配符" }
            topic = t
        }

        fun payload(data: String) = apply {
            require(data.isNotBlank()) { "消息不能为空" }
            payload = data.toByteArray()
        }

        fun qos(level: Int) = apply {
            require(level in 0..2) { "QoS等级必须为0、1、2" }
            qos = level
        }

        fun retained(retained: Boolean) = apply {
            this.retained = retained
        }

        fun validate() {
            requireNotNull(topic) { "遗嘱主题不能为空" }
            require(qos in 0..2) { "遗嘱QoS等级无效" }
        }
    }
}