package com.ok.mqtt

import android.os.Binder

class MqttServiceBinder(val service: MqttService) : Binder() {

    var activityToken: String? = null

}

