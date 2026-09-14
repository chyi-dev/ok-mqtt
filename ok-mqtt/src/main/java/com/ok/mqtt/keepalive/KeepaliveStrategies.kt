package com.ok.mqtt.keepalive

import android.content.Context

/**
 * Builds the default strategy set from [KeepaliveConfig].
 */
object KeepaliveStrategies {
    fun fromConfig(context: Context, config: KeepaliveConfig): List<KeepaliveStrategy> {
        return buildList {
            if (config.enableLifecycle) add(LifecycleStrategy())
            if (config.enableAlarm) add(AlarmPingStrategy(context))
            if (config.enableWorkManager) add(WorkManagerStrategy(context))
            if (config.enableForegroundService) add(ForegroundServiceStrategy(context, config))
        }
    }
}
