package com.scardracs.sonai.health

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

class HeartRateMonitor(context: Context) {
    private val client = HealthServices.getClient(context).measureClient

    fun heartRateFlow() = callbackFlow {
        val callback = object : MeasureCallback {
            override fun onDataReceived(data: DataPointContainer) {
                val heartRate = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
                if (heartRate != null) {
                    trySend(heartRate.toInt())
                }
            }

            override fun onAvailabilityChanged(
                dataType: DeltaDataType<*, *>,
                availability: androidx.health.services.client.data.Availability
            ) {
                /* No-op: handled by data flow */
            }
        }

        client.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        awaitClose {
            client.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
        }
    }
}
