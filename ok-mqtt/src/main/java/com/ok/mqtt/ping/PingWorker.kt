package com.ok.mqtt.ping

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ok.mqtt.ping.AlarmPingSender.Companion.sdf
import com.ok.mqtt.room.entity.PingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import java.util.Date
import kotlin.coroutines.resume

class PingWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "PingWorker"
        const val LOGGING = "logging"
        const val KEEP_RECORDS_COUNT = "keepCount"
    }

    override suspend fun doWork(): Result =
        suspendCancellableCoroutine { continuation ->

            val logging = inputData.getBoolean(LOGGING, false)
            val keepRecords = inputData.getInt(KEEP_RECORDS_COUNT, 1000)
            val key = this.inputData.getString("id")
            Log.d(TAG, "$key Sending Ping at: ${sdf.format(Date(System.currentTimeMillis()))}")

            //check if id is not null
            if (key == null) {
                Log.e(TAG, "connection id in ping worker is null!")
                continuation.resume(Result.failure())
                return@suspendCancellableCoroutine
            }

            //check if there is a clients comm asociated with the key
            if (!AlarmPingSender.clientCommsMap.containsKey(key)) {
                Log.e(TAG, "client comm doesn't exist anymore: $key")
                continuation.resume(Result.failure())
                return@suspendCancellableCoroutine
            }

            AlarmPingSender.clientCommsMap[key]?.checkForActivity(object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "$key Ping Success ${asyncActionToken?.client?.clientId}")
                    if (logging) {
                        val pingMQ = PingEntity(
                            System.currentTimeMillis(),
                            asyncActionToken?.client?.clientId,
                            asyncActionToken?.client?.serverURI,
                            true
                        )
                        CoroutineScope(Dispatchers.IO).launch {
                            AlarmPingSender.messageDatabase?.pingDao()?.insert(pingMQ)
                            AlarmPingSender.messageDatabase?.pingDao()?.removeOldData(keepRecords)
                        }
                    }
                    continuation.resume(Result.success())
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "$key Ping Failure $exception ${asyncActionToken?.client?.clientId}")
                    if (logging) {
                        val pingMQ = PingEntity(
                            System.currentTimeMillis(),
                            asyncActionToken?.client?.clientId,
                            asyncActionToken?.client?.serverURI,
                            false,
                            exception?.message
                        )
                        CoroutineScope(Dispatchers.IO).launch {
                            AlarmPingSender.messageDatabase?.pingDao()?.insert(pingMQ)
                            AlarmPingSender.messageDatabase?.pingDao()?.removeOldData(keepRecords)
                        }
                    }
                    continuation.resume(Result.failure())
                }
            }) ?: kotlin.run {
                // when token is null doesn't always mean a failure sometimes there wasn't a need
                // send a ping request yet
                continuation.resume(Result.success())
            }
        }
}

