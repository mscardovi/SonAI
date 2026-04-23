package com.sonai.sonai.service

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WearListenerService : WearableListenerService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var healthConnectManager: HealthConnectManager

    override fun onCreate() {
        super.onCreate()
        healthConnectManager = HealthConnectManager(this)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val command = String(messageEvent.data)
        Log.d("WearListener", "Received command: $command")
        if (messageEvent.path == "/command") {
            handleCommand(command)
        } else if (messageEvent.path == "/status" && command == "GET_STATUS") {
            scope.launch {
                val status = if (SoundAnalysisService.instance != null) "PLAYING" else "IDLE"
                WearCommunicationManager(this@WearListenerService).sendStatus(status)
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                if (path == "/heart_rate") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val heartRate = dataMap.getInt("bpm")
                    Log.d("WearListener", "Received heart rate: $heartRate")
                    
                    scope.launch {
                        healthConnectManager.writeHeartRate(heartRate)
                    }
                    
                    broadcastHeartRate(heartRate)
                }
            }
        }
    }

    private fun handleCommand(command: String) {
        when (command) {
            "START" -> {
                val intent = Intent(this, SoundAnalysisService::class.java).apply {
                    // You might want to pass default modes here or get them from preferences
                    putExtra("EXTRA_MODES", arrayOf("AUTO"))
                }
                startForegroundService(intent)
            }
            "STOP" -> {
                stopService(Intent(this, SoundAnalysisService::class.java))
            }
        }
    }

    private fun broadcastHeartRate(heartRate: Int) {
        val intent = Intent("HeartRateUpdate")
        intent.putExtra("bpm", heartRate)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
