package com.ok.mqtt.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointParseTest {

    @Test
    fun parseTcp() {
        val e = HiveMqttTransport.parseEndpoint("tcp://broker.hivemq.com:1883")
        assertEquals("broker.hivemq.com", e.host)
        assertEquals(1883, e.port)
        assertFalse(e.ssl)
        assertFalse(e.websocket)
    }

    @Test
    fun parseSslDefaultPort() {
        val e = HiveMqttTransport.parseEndpoint("ssl://broker.example.com")
        assertEquals(8883, e.port)
        assertTrue(e.ssl)
    }

    @Test
    fun parseWs() {
        val e = HiveMqttTransport.parseEndpoint("ws://broker.example.com:8000")
        assertTrue(e.websocket)
        assertFalse(e.ssl)
        assertEquals(8000, e.port)
    }

    @Test
    fun parseBareHost() {
        val e = HiveMqttTransport.parseEndpoint("127.0.0.1:1883")
        assertEquals("127.0.0.1", e.host)
        assertEquals(1883, e.port)
    }
}
