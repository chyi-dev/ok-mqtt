package com.ok.mqtt.demo

import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ok.mqtt.Ack
import com.ok.mqtt.MqttAndroidClient
import com.ok.mqtt.MqttTraceHandler
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * MQTT 客户端示例
 *
 * @author Leyi
 * @date 2025/4/17 10:10
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
    private lateinit var btnConnect: TextView
    private lateinit var btnSubmit: TextView

    private var mqttClient: MqttAndroidClient? = null
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
    }

    private fun initViews() {
        etUrl = findViewById(R.id.et_url)
        etPort = findViewById(R.id.et_port)
        etUser = findViewById(R.id.et_user)
        etPwd = findViewById(R.id.et_pwd)
        etClientId = findViewById(R.id.et_client_id)
        btnConnect = findViewById(R.id.btn_connect)
        btnSubmit = findViewById(R.id.btn_submit)

        // 设置默认值（可选）
        etUrl.setText("tcp://ops.coffeeji.com")
        etPort.setText("3000")
        etUser.setText("test")
        etPwd.setText("test")
        etClientId.setText("android_client_${System.currentTimeMillis()}")
    }

    private fun setupClickListeners() {
        btnConnect.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                connect()
            }
        }

        btnSubmit.setOnClickListener {
            if (isConnected) {
                subscribeToTopic()
            } else {
                Toast.makeText(this, "请先连接MQTT服务器", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun connect() {
        val url = etUrl.text.toString().trim()
        val port = etPort.text.toString().trim()
        val user = etUser.text.toString().trim()
        val pwd = etPwd.text.toString().trim()
        val clientId = etClientId.text.toString().trim()

        if (url.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }

        if (clientId.isEmpty()) {
            Toast.makeText(this, "请输入ClientId", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // 构建完整的服务器URI
            val serverURI = if (url.startsWith("tcp://") || url.startsWith("ssl://")) {
                "$url:$port"
            } else {
                "tcp://$url:$port"
            }

            // 创建 MQTT 客户端
            mqttClient = MqttAndroidClient(
                applicationContext,
                serverURI,
                clientId,
                Ack.AUTO_ACK
            )

            // 设置回调
            mqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    runOnUiThread {
                        Log.d(TAG, "连接完成: reconnect=$reconnect, serverURI=$serverURI")
                        Toast.makeText(this@MainActivity, "连接成功", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    runOnUiThread {
                        Log.e(TAG, "连接丢失", cause)
                        isConnected = false
                        updateConnectButton()
                        Toast.makeText(this@MainActivity, "连接丢失: ${cause?.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    runOnUiThread {
                        val payload = String(message.payload)
                        Log.d(TAG, "收到消息 - Topic: $topic, Payload: $payload")
                        Toast.makeText(
                            this@MainActivity,
                            "收到消息\nTopic: $topic\n内容: $payload",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken) {
                    Log.d(TAG, "消息发送完成")
                }
            })

            // 设置跟踪回调（可选）
            mqttClient?.setTraceCallback(object : MqttTraceHandler {
                override fun traceDebug(message: String?) {
                    Log.d(TAG, "Trace Debug: $message")
                }

                override fun traceError(message: String?) {
                    Log.e(TAG, "Trace Error: $message")
                }

                override fun traceException(message: String?, e: Exception?) {
                    Log.e(TAG, "Trace Exception: $message", e)
                }
            })

            // 配置连接选项
            val connectOptions = MqttConnectOptions()
            connectOptions.isCleanSession = true
            connectOptions.isAutomaticReconnect = true
            connectOptions.connectionTimeout = 30
            connectOptions.keepAliveInterval = 60

            // 设置用户名和密码（如果有）
            if (user.isNotEmpty()) {
                connectOptions.userName = user
            }
            if (pwd.isNotEmpty()) {
                connectOptions.password = pwd.toCharArray()
            }

            // 连接
            btnConnect.isEnabled = false
            btnConnect.text = "连接中..."
            
            mqttClient?.connect(connectOptions, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    runOnUiThread {
                        Log.d(TAG, "连接成功")
                        isConnected = true
                        updateConnectButton()
                        Toast.makeText(this@MainActivity, "连接成功", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    runOnUiThread {
                        Log.e(TAG, "连接失败", exception)
                        isConnected = false
                        updateConnectButton()
                        Toast.makeText(
                            this@MainActivity,
                            "连接失败: ${exception?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "创建MQTT客户端失败", e)
            Toast.makeText(this, "创建客户端失败: ${e.message}", Toast.LENGTH_SHORT).show()
            updateConnectButton()
        }
    }

    private fun disconnect() {
        try {
            mqttClient?.disconnect(null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    runOnUiThread {
                        Log.d(TAG, "断开连接成功")
                        isConnected = false
                        updateConnectButton()
                        Toast.makeText(this@MainActivity, "已断开连接", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    runOnUiThread {
                        Log.e(TAG, "断开连接失败", exception)
                        Toast.makeText(
                            this@MainActivity,
                            "断开连接失败: ${exception?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "断开连接异常", e)
        }
    }

    private fun subscribeToTopic() {
        // 这里可以添加一个对话框让用户输入要订阅的主题
        // 为了示例，我们订阅一个默认主题
        val topic = "test/topic" // 可以改为从输入框获取
        
        try {
            mqttClient?.subscribe(topic, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    runOnUiThread {
                        Log.d(TAG, "订阅成功: $topic")
                        Toast.makeText(this@MainActivity, "订阅成功: $topic", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    runOnUiThread {
                        Log.e(TAG, "订阅失败: $topic", exception)
                        Toast.makeText(
                            this@MainActivity,
                            "订阅失败: ${exception?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "订阅异常", e)
            Toast.makeText(this, "订阅异常: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun publishMessage(topic: String, message: String, qos: Int = 1) {
        try {
            val mqttMessage = MqttMessage(message.toByteArray())
            mqttMessage.qos = qos
            mqttMessage.isRetained = false

            mqttClient?.publish(topic, mqttMessage, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    runOnUiThread {
                        Log.d(TAG, "发布成功: $topic")
                        Toast.makeText(this@MainActivity, "发布成功", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    runOnUiThread {
                        Log.e(TAG, "发布失败: $topic", exception)
                        Toast.makeText(
                            this@MainActivity,
                            "发布失败: ${exception?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "发布异常", e)
            Toast.makeText(this, "发布异常: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateConnectButton() {
        btnConnect.isEnabled = true
        btnConnect.text = if (isConnected) "断开连接" else "连接"
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理资源
        try {
            mqttClient?.unregisterResources()
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "清理MQTT客户端失败", e)
        }
    }
}
