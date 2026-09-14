package com.ok.mqtt

interface MqttTraceHandler {

    fun traceDebug(message: String?)

    fun traceError(message: String?)

    fun traceException(message: String?, e: Exception?)
}

