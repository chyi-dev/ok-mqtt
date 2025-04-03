package com.ok.mqtt

import android.content.Context
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * MQTT管理类
 *
 * @author Leyi
 * @date 2025/2/25 15:04
 * 使用流程：
 * 1，配置MqttConfig，设置账号和密码，MqttConfig().create()。
 * 2，初始化Mqtt客户端，MqttManager.getInstance().init(activity, MqttConfig)，建议在Application的onCreate或MainActivity的onCreate中调用。
 * 3，连接Mqtt客户端，MqttManager.getInstance().connect()，必须在init方法后调用。
 * 4，订阅Topic，MqttManager.getInstance().subscribe(topic, subscriber)，并在subscriber中处理消息的回调。
 * 5，发布消息，MqttManager.getInstance().publishMessage(topic,content)。
 * 6，退订Topic，MqttManager.getInstance().unsubscribe(topic)，建议在页面消失时调用。
 * 7，关闭Mqtt，MqttManager.getInstance().close()，建议在MainActivity的onDestroy中调用或应用退出时。
 */
class OkMqtt {

    companion object {
        @Volatile
        private var instance: OkMqtt? = null

        fun getInstance(): OkMqtt {
            return instance ?: synchronized(this) {
                val okMqtt = OkMqtt()
                instance = okMqtt
                okMqtt
            }
        }
    }

    private lateinit var mConfig: MqttConfig
    private var mqttClient: MqttAndroidClient? = null
    private var onConnectionListener: OnConnectionListener? = null
    private var onSubscriberListener: OnSubscriberListener? = null
    private var onMessageListener: OnMessageListener? = null
    private val subscribedTopics = mutableSetOf<String>()
    private val topicMessageListener = LinkedHashMap<String, OnTopicReceivedListener>()

    /**
     * 初始化Mqtt客户端，建议在MainActivity的onCreate中调用
     */
    fun init(context: Context, config: MqttConfig) {
        mConfig = config
        mqttClient = MqttAndroidClient(context, config.serverUri, config.clientId)
        mqttClient!!.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                if (reconnect) {
                    MqttLogger.i("----> mqtt reconnect complete, serverUrl = $serverURI")
                } else {
                    MqttLogger.i("----> mqtt connect complete, serverUrl = $serverURI")
                }
                onConnectionListener?.connectComplete(reconnect, serverURI)
            }

            override fun connectionLost(cause: Throwable?) {
                onConnectionListener?.onConnectionLost(cause)
                MqttLogger.i("----> mqtt connect lost, cause = ${cause?.message}")
            }

            @Throws(Exception::class)
            override fun messageArrived(topic: String, message: MqttMessage) {
                onMessageListener?.onMessageReceived(topic, String(message.payload))
                topicMessageListener[topic]?.onMessageReceived(String(message.payload))
                MqttLogger.i("----> mqtt message arrived, topic = $topic, message = ${String(message.payload)}")
            }

            override fun deliveryComplete(token: IMqttDeliveryToken) {
                MqttLogger.i("----> mqtt delivery complete, token = ${token.message}")
                onMessageListener?.deliveryComplete(String(token.message.payload))
            }
        })
    }


    /**
     * 连接服务器
     * @param callback 表示当前方法的回调，并不会作用到全局
     */
    fun connect(callback: OnConnectionListener? = null) {
        if (mqttClient == null) {
            callback?.onConnectFailed(IllegalStateException("mqtt connect failed, please init mqtt first"))
            onConnectionListener?.onConnectFailed(IllegalStateException("mqtt connect failed, please init mqtt first"))
            MqttLogger.e("----> mqtt connect failed, please init mqtt first.")
            return
        }
        try {
            mqttClient?.connect(generateConnectOptions(), null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    onConnectionListener?.onConnected()
                    callback?.onConnected()
                    MqttLogger.i("----> mqtt connect success.")
                    val disconnectedBufferOptions = DisconnectedBufferOptions()
                    disconnectedBufferOptions.isBufferEnabled = true
                    disconnectedBufferOptions.bufferSize = 100
                    disconnectedBufferOptions.isPersistBuffer = false
                    disconnectedBufferOptions.isDeleteOldestMessages = false
                    mqttClient?.setBufferOpts(disconnectedBufferOptions)
                }

                override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable?) {
                    onConnectionListener?.onConnectFailed(exception)
                    callback?.onConnectFailed(exception)
                    MqttLogger.e("----> mqtt connect failed, onFailure exception = ${exception?.message}")
                }
            })
        } catch (ex: Exception) {
            MqttLogger.e("----> mqtt connect failed, catch exception = ${ex.message}")
            callback?.onConnectFailed(ex)
            onConnectionListener?.onConnectFailed(ex)
            ex.printStackTrace()
        }
    }

    /**
     * 订阅一个话题
     */
    fun subscribe(topic: String, qos: Int = 1, callback: OnSubscriberListener? = null) {
        if (mqttClient == null) {
            MqttLogger.e("----> mqtt subscribe failed, please init mqtt first.")
            return
        }
        if (subscribedTopics.contains(topic) && !mConfig.isCleanSession) {
            callback?.onSubscribeSuccess(topic)
            return
        }
        if (isConnected()) {
            performSubscribe(topic, qos, callback)
        } else {
            disconnect()
            // 如果没有连接，就先去连接
            connect(object : OnConnectionListener {
                override fun onConnected() {
                    performSubscribe(topic, qos, callback)
                }
            })
        }
    }

    /**
     * 订阅实现
     */
    private fun performSubscribe(topic: String, qos: Int, callback: OnSubscriberListener? = null) {
        try {
            mqttClient?.subscribe(topic, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    callback?.onSubscribeSuccess(topic)
                    subscribedTopics.add(topic)
                    MqttLogger.i("----> mqtt subscribe success, topic = $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable?) {
                    callback?.onSubscribeFailed(topic, exception)
                    subscribedTopics.remove(topic)
                    MqttLogger.e("----> mqtt subscribe failed, exception = ${exception?.message}")
                }
            })
        } catch (ex: MqttException) {
            callback?.onSubscribeFailed(topic, ex)
            MqttLogger.e("----> mqtt subscribe failed, exception = ${ex.message}")
            ex.printStackTrace()
        }
    }

    /**
     * 退订某一个topic
     */
    fun unsubscribe(topic: String) {
        subscribedTopics.remove(topic)
        mqttClient?.unsubscribe(topic, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken) {
                MqttLogger.i("----> mqtt unsubscribe success, topic = $topic")
            }

            override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable?) {
                MqttLogger.e("----> mqtt unsubscribe failed, exception = ${exception?.message}")
            }
        })
    }


    /**
     * 发布消息
     */
    fun publishMessage(topic: String, content: String) {
        if (mqttClient == null) {
            MqttLogger.e("----> mqtt publish message failed, please init mqtt first.")
            return
        }
        if (isConnected()) {
            performPublishMessage(topic, content)
        } else {
            // 如果没有连接，就先去连接
            connect(object : OnConnectionListener {
                override fun onConnected() {
                    performPublishMessage(topic, content)
                }
            })
        }
    }


    private fun performPublishMessage(topic: String, content: String) {
        try {
            val message = MqttMessage()
            message.payload = content.toByteArray()
            mqttClient?.publish(topic, message)
        } catch (e: MqttException) {
            MqttLogger.e("----> mqtt publish message failed, exception = ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 添加连接监听
     * @param onConnectionListener OnConnectionListener
     */
    fun addConnectionListener(onConnectionListener: OnConnectionListener) {
        this.onConnectionListener = onConnectionListener
    }

    /**
     * 添加订阅监听
     * @param onSubscriberListener OnSubscriberListener
     */
    fun addSubscriberListener(onSubscriberListener: OnSubscriberListener) {
        this.onSubscriberListener = onSubscriberListener
    }

    /**
     * 添加消息到达监听，不区分topic
     * @param onMessageListener OnMessageListener
     */
    fun addMessageListener(onMessageListener: OnMessageListener) {
        this.onMessageListener = onMessageListener
    }

    /**
     * 添加消息到达监听，需指定topic
     * @param topic String
     * @param onTopicReceivedListener OnTopicReceivedListener
     */
    fun addMessageListener(topic: String, onTopicReceivedListener: OnTopicReceivedListener) {
        if (!topicMessageListener.contains(topic)) {
            topicMessageListener[topic] = onTopicReceivedListener
        }
    }

    /**
     * 移除连接监听
     */
    fun removeConnectionListener() {
        onConnectionListener = null
    }

    /**
     * 移除订阅监听
     */
    fun removeSubscriberListener() {
        onSubscriberListener = null
    }

    /**
     * 移除消息到达监听，不区分topic
     */
    fun removeMessageListener() {
        onMessageListener = null
    }

    /**
     * 移除消息到达监听，需指定topic
     * @param topic String
     */
    fun removeMessageListener(topic: String) {
        topicMessageListener.remove(topic)
    }

    /**
     * 主动断开连接，不会自动重连
     */
    fun disconnect() {
        try {
            mqttClient?.disconnect()
            onConnectionListener?.onDisconnected()
            MqttLogger.i("----> mqtt disconnect success.")
        } catch (e: Exception) {
            MqttLogger.e("----> mqtt disconnect failed, exception = ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 关闭MQTT客户端，建议在MainActivity的onDestroy中调用
     */
    fun close() {
        try {
            // 正常断开（等待操作完成）
            mqttClient?.disconnect()
            // 释放资源（必须调用）
            mqttClient?.close()
            mqttClient?.unregisterResources()
            onConnectionListener?.onDisconnected()
            subscribedTopics.clear()
            topicMessageListener.clear()
            removeSubscriberListener()
            removeMessageListener()
            removeConnectionListener()
            MqttLogger.i("----> mqtt close success.")
        } catch (e: Exception) {
            MqttLogger.e("----> mqtt close failed, exception = ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 判断连接是否断开
     */
    fun isConnected(): Boolean {
        try {
            return mqttClient?.isConnected ?: false
        } catch (e: Exception) {
            MqttLogger.e("----> mqtt connect error, exception = ${e.message}")
            e.printStackTrace()
        }
        return false
    }

    /**
     * 生成默认的连接配置
     */
    private fun generateConnectOptions(): MqttConnectOptions {
        return mConfig.toConnectOptions()
    }
}