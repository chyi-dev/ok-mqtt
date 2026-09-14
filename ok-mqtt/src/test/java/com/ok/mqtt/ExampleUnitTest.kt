package com.ok.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class QosTest {
    @Test
    fun fromCode() {
        assertEquals(Qos.AtMostOnce, Qos.from(0))
        assertEquals(Qos.AtLeastOnce, Qos.from(1))
        assertEquals(Qos.ExactlyOnce, Qos.from(2))
    }
}
