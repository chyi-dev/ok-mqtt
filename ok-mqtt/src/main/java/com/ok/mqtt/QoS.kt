package com.ok.mqtt

/**
 * Quality of Service levels.
 */
enum class Qos(val code: Int) {
    AtMostOnce(0),
    AtLeastOnce(1),
    ExactlyOnce(2);

    companion object {
        fun from(code: Int): Qos = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Invalid QoS: $code")
    }
}
