package com.ok.mqtt

/**
 * MQTT监听接口
 * @author Leyi
 * @date 2025/2/25 15:03
 */
interface OnConnectionListener {
    fun onConnected()
    fun connectComplete(reconnect: Boolean, serverURI: String){}
    fun onConnectFailed(exception: Throwable?) {}
    fun onDisconnected() {}
    fun onConnectionLost(cause: Throwable?) {}
}

interface OnSubscriberListener {
    fun onSubscribeSuccess(topic: String)
    fun onSubscribeFailed(topic: String, exception: Throwable?) {}
}

interface OnMessageListener {
    fun onMessageReceived(topic: String, message: String)

    fun deliveryComplete(message: String) {}
}

interface OnTopicReceivedListener {
    fun onMessageReceived(message: String)
}