package com.ok.mqtt.demo

import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.TimeUtils
import com.ok.mqtt.Auth
import com.ok.mqtt.MqttState
import com.ok.mqtt.MqttVersion
import com.ok.mqtt.OkMqttClient
import com.ok.mqtt.OkMqttConfig
import com.ok.mqtt.Qos
import com.ok.mqtt.SessionOptions
import com.ok.mqtt.keepalive.KeepaliveConfig
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Demo / scenario panel for ok-mqtt (HiveMQ-backed).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var etUrl: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPwd: EditText
    private lateinit var etClientId: EditText
    private lateinit var etTopic: EditText
    private lateinit var etPayload: EditText
    private lateinit var btnConnect: TextView
    private lateinit var btnSubscribe: TextView
    private lateinit var btnPublish: TextView
    private lateinit var btnVersion: TextView
    private lateinit var btnFgs: TextView
    private lateinit var tvState: TextView
    private lateinit var tvLog: TextView

    private var mqttClient: OkMqttClient? = null
    private var mqttVersion = MqttVersion.V5
    private var enableFgs = false
    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        setupClickListeners()
        updateVersionLabel()
        updateFgsLabel()
        appendLog("Ready. Protocol=${mqttVersion.name}")
    }

    private fun initViews() {
        etUrl = findViewById(R.id.et_url)
        etPort = findViewById(R.id.et_port)
        etUser = findViewById(R.id.et_user)
        etPwd = findViewById(R.id.et_pwd)
        etClientId = findViewById(R.id.et_client_id)
        etTopic = findViewById(R.id.et_topic)
        etPayload = findViewById(R.id.et_payload)
        btnConnect = findViewById(R.id.btn_connect)
        btnSubscribe = findViewById(R.id.btn_submit)
        btnPublish = findViewById(R.id.btn_publish)
        btnVersion = findViewById(R.id.btn_version)
        btnFgs = findViewById(R.id.btn_fgs)
        tvState = findViewById(R.id.tv_state)
        tvLog = findViewById(R.id.tv_log)

        etUrl.setText("8.154.23.241")
        etPort.setText("1883")
        etClientId.setText("client_0a4e5cbf789819cb")
        etUser.setText("0a4e5cbf789819cb")
        etPwd.setText("8c253fb02f3d1db89190b3a730e2931a")
        etTopic.setText("0a4e5cbf789819cbdevState")
        etPayload.setText("{\"device\":\"0a4e5cbf789819cb\",\"time\":\"2026-09-14 11:37:42\",\"timestamp\":\"1789357062273\",\"today\":\"316.128 KB\"}")
    }

    private fun setupClickListeners() {
        btnConnect.setOnClickListener {
            if (mqttClient != null && mqttClient!!.state.value is MqttState.Connected) {
                mqttClient?.disconnect()
            } else {
                connectMqtt()
            }
        }
        btnSubscribe.setOnClickListener {
            val topic = etTopic.text.toString().trim()
            if (topic.isEmpty()) {
                toast("请输入主题")
                return@setOnClickListener
            }
            mqttClient?.subscribe(topic, Qos.AtLeastOnce)
            appendLog("SUBSCRIBE $topic")
        }
        btnPublish.setOnClickListener {
            val topic = etTopic.text.toString().trim()
            val payload = etPayload.text.toString()
            mqttClient?.publish(topic, payload, Qos.AtLeastOnce)
            appendLog("PUBLISH $topic → $payload")
        }
        btnVersion.setOnClickListener {
            mqttVersion = if (mqttVersion == MqttVersion.V5) MqttVersion.V3_1_1 else MqttVersion.V5
            updateVersionLabel()
            appendLog("Protocol switched to ${mqttVersion.name} (reconnect to apply)")
        }
        btnFgs.setOnClickListener {
            enableFgs = !enableFgs
            updateFgsLabel()
            appendLog("FGS=${enableFgs} (reconnect to apply)")
        }
    }

    private fun connectMqtt() {
        mqttClient?.close()
        val host = etUrl.text.toString().trim()
        val port = etPort.text.toString().trim().ifEmpty { "1883" }
        val clientId = etClientId.text.toString().trim().ifEmpty { "ok-mqtt-demo" }
        val user = etUser.text.toString().trim()
        val pwd = etPwd.text.toString()

        val auth = if (user.isNotEmpty()) {
            Auth.Simple(user, pwd.toByteArray(Charsets.UTF_8))
        } else {
            Auth.None
        }

        val config = OkMqttConfig(
            serverUri = "tcp://$host:$port",
            clientId = clientId,
            version = mqttVersion,
            auth = auth,
            session = SessionOptions(cleanStart = true, keepAliveSeconds = 60),
            keepalive = KeepaliveConfig(
                enableForegroundService = enableFgs,
                enableAlarm = true,
                enableLifecycle = true,
                enableWorkManager = false,
                notificationTitle = "ok-mqtt demo",
                notificationText = "MQTT keepalive active"
            )
        )

        val client = OkMqttClient(applicationContext, config)
        mqttClient = client
        appendLog("Connecting ${config.serverUri} as $clientId [${mqttVersion.name}]")

        lifecycleScope.launch {
            client.state.collectLatest { state ->
                runOnUiThread {
                    tvState.text = "状态: ${stateLabel(state)}"
                    btnConnect.text = if (state is MqttState.Connected) "断开" else "连接"
                    appendLog("STATE → ${stateLabel(state)}")
                }
            }
        }
        lifecycleScope.launch {
            client.incoming.collectLatest { msg ->
                val body = msg.payload.toString(Charsets.UTF_8)
                appendLog("MSG ${msg.topic}: $body")
            }
        }
        client.connect()
    }

    private fun stateLabel(state: MqttState): String = when (state) {
        is MqttState.Idle -> "Idle"
        is MqttState.Connecting -> "Connecting"
        is MqttState.Connected -> "Connected (reconnect=${state.reconnect})"
        is MqttState.Reconnecting -> "Reconnecting #${state.attempt} in ${state.nextDelayMs}ms"
        is MqttState.Failed -> "Failed: ${state.cause}"
        is MqttState.Disconnected -> "Disconnected"
    }

    private fun updateVersionLabel() {
        btnVersion.text = "协议: ${mqttVersion.name}"
    }

    private fun updateFgsLabel() {
        btnFgs.text = if (enableFgs) "FGS: ON" else "FGS: OFF"
    }

    private fun appendLog(line: String) {
        Log.d(TAG, line)
        logLines.addFirst("${TimeUtils.getNowString()}: $line")
        while (logLines.size > 40) logLines.removeLast()
        runOnUiThread {
            tvLog.text = logLines.joinToString("\n")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        mqttClient?.close()
        mqttClient = null
        super.onDestroy()
    }
}
