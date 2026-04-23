package com.sonai.sonai.service

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/status") {
            val status = String(messageEvent.data)
            broadcastStatus(status)
        }
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent("SoundAnalysisStatus")
        intent.putExtra("status", status)
        sendBroadcast(intent)
    }
}
