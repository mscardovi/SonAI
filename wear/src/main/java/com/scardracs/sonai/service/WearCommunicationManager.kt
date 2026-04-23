package com.scardracs.sonai.service

import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

class WearCommunicationManager(private val context: Context) {
    private val dataClient = Wearable.getDataClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    suspend fun sendHeartRate(heartRate: Int) {
        val request = PutDataMapRequest.create("/heart_rate").run {
            dataMap.putInt("bpm", heartRate)
            dataMap.putLong("timestamp", System.currentTimeMillis())
            asPutDataRequest()
        }
        try {
            dataClient.putDataItem(request).await()
        } catch (e: ApiException) {
            Log.e("WearComm", "Failed to send heart rate", e)
        }
    }

    suspend fun sendCommand(command: String) {
        try {
            val nodes = nodeClient.connectedNodes.await()
            for (node in nodes) {
                messageClient.sendMessage(node.id, "/command", command.toByteArray()).await()
            }
        } catch (e: ApiException) {
            Log.e("WearComm", "Failed to send command: $command", e)
        }
    }

    suspend fun sendStatus(status: String) {
        try {
            val nodes = nodeClient.connectedNodes.await()
            for (node in nodes) {
                messageClient.sendMessage(node.id, "/status", status.toByteArray()).await()
            }
        } catch (e: ApiException) {
            Log.e("WearComm", "Failed to send status: $status", e)
        }
    }
}
