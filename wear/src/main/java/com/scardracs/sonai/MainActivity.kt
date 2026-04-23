package com.scardracs.sonai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.scardracs.sonai.health.HeartRateMonitor
import com.scardracs.sonai.service.WearCommunicationManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearApp()
        }
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val heartRateMonitor = remember { HeartRateMonitor(context) }
    val commManager = remember { WearCommunicationManager(context) }

    var isPlaying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Request current status on startup
    LaunchedEffect(Unit) {
        scope.launch {
            commManager.sendCommand("GET_STATUS")
        }
    }

    val statusReceiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: android.content.Context?,
                intent: android.content.Intent?
            ) {
                val status = intent?.getStringExtra("status")
                isPlaying = (status == "PLAYING")
            }
        }
    }

    LaunchedEffect(Unit) {
        val flags =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                android.content.Context.RECEIVER_NOT_EXPORTED
            } else {
                0
            }
        context.registerReceiver(
            statusReceiver,
            android.content.IntentFilter("SoundAnalysisStatus"),
            flags
        )
    }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BODY_SENSORS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionGranted = isGranted
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            launcher.launch(Manifest.permission.BODY_SENSORS)
        }
    }

    val heartRate by if (permissionGranted) {
        heartRateMonitor.heartRateFlow().collectAsState(initial = null)
    } else {
        remember { mutableStateOf(null) }
    }

    LaunchedEffect(heartRate) {
        heartRate?.let {
            commManager.sendHeartRate(it)
        }
    }

    MaterialTheme {
        WearMainScaffold(heartRate, isPlaying, commManager)
    }
}

@Composable
private fun WearMainScaffold(
    heartRate: Int?,
    isPlaying: Boolean,
    commManager: WearCommunicationManager
) {
    val scope = rememberCoroutineScope()
    Scaffold(
        timeText = { TimeText() }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(8.dp)
            ) {
                Text(text = "SonAI", style = MaterialTheme.typography.caption1)

                Text(
                    text = if (heartRate != null) "$heartRate BPM" else "-- BPM",
                    style = MaterialTheme.typography.display3
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row {
                    Button(
                        onClick = { scope.launch { commManager.sendCommand("START") } },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
                        enabled = !isPlaying
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { scope.launch { commManager.sendCommand("STOP") } },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                        enabled = isPlaying
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                }
            }
        }
    }
}
