package com.scardracs.sonai.service

import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

class WearCommunicationManager(context: Context) {
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    suspend fun sendStatus(status: String) {
        try {
            val nodes = nodeClient.connectedNodes.await()
            for (node in nodes) {
                Log.d("WearComm", "Sending status $status to node ${node.displayName}")
                messageClient.sendMessage(node.id, "/status", status.toByteArray()).await()
            }
        } catch (e: ApiException) {
            Log.e("WearComm", "Failed to send status: $status", e)
        }
    }
}
