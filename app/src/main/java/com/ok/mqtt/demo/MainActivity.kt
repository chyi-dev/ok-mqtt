package com.ok.mqtt.demo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ok.mqtt.MqttConfig
import com.ok.mqtt.OkMqtt

/**
 *
 * @author Leyi
 * @date 2025/4/17 10:10
 */
class MainActivity :AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = MqttConfig.create {
            serverUri("")
            clientId("tools")
            keepAliveInterval(60)
            credentials("test", "test")
            lastWill {
                topic("")
                qos(1)
                payload("")
                retained(true)
            }
        }
        OkMqtt.getInstance().init(this, config)
    }
}