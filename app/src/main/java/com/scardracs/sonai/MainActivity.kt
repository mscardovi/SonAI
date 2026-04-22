package com.scardracs.sonai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.scardracs.sonai.audio.NoiseGenerator
import com.scardracs.sonai.service.SoundAnalysisService
import com.scardracs.sonai.ui.components.WaveformComposable
import com.scardracs.sonai.ui.theme.SonAITheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var isMonitoring by mutableStateOf(false)
    private var detectedLabel by mutableStateOf("")
    private var maskingSuggestion by mutableStateOf("")
    private var noiseLevel by mutableIntStateOf(0)
    private var selectedTimerMinutes by mutableIntStateOf(0)
    private val magnitudes = mutableStateListOf<Float>().apply {
        repeat(50) { add(0.1f) }
    }
    
    private val allModeKeys = arrayOf("AUTO", "DEEP_SPACE", "STELLAR_WIND", "EARTH_RUMBLE", "RAIN_FOREST", "OCEAN_WAVES")
    private val timerOptions = listOf(0, 15, 30, 45, 60, 90, 120)
    private var selectedModes by mutableStateOf(setOf("AUTO"))

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            detectedLabel = intent?.getStringExtra("label") ?: ""
            maskingSuggestion = intent?.getStringExtra("masking") ?: ""
            val amplitude = intent?.getFloatExtra("amplitude", 0.1f) ?: 0.1f
            noiseLevel = intent?.getIntExtra("db", 0) ?: 0
            val isAuto = intent?.getBooleanExtra("is_auto", true) ?: true
            
            // Sync current state from service
            if (isMonitoring) {
                val currentModes = maskingSuggestion.split(", ")
                // We don't want to overwrite user selection while they are in the dialog,
                // but we need to know if service is in auto mode
            }

            // Auto-scroll timer slider as time passes
            val seconds = intent?.getIntExtra("timer_seconds", -1) ?: -1
            if (seconds >= 0) {
                selectedTimerMinutes = (seconds / 60f).roundToInt()
            }

            magnitudes.removeAt(0)
            magnitudes.add(amplitude.coerceIn(0.1f, 1.0f))
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startSoundAnalysisService()
        } else {
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SonAITheme {
                MainScreen()
            }
        }

        if (checkPermissions()) {
            startSoundAnalysisService()
        } else {
            requestPermissions()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        var showDialog by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (isMonitoring) {
                        if (detectedLabel.isNotEmpty()) 
                            stringResource(R.string.status_detected, detectedLabel)
                        else 
                            stringResource(R.string.monitoring_active)
                    } else {
                        stringResource(R.string.status_idle)
                    },
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    text = if (isMonitoring) 
                        stringResource(R.string.masking_type, maskingSuggestion)
                    else 
                        stringResource(R.string.suggestion_default)
                )

                Text(text = stringResource(R.string.noise_level, noiseLevel))
                
                if (isMonitoring && selectedModes.contains("AUTO")) {
                    Text(
                        text = stringResource(R.string.smart_volume_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                WaveformComposable(magnitudes = magnitudes)

                OutlinedCard(
                    onClick = { showDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.masking_modes_hint),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = selectedModes.joinToString(", ") { getModeDisplayName(it) },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Icon(Icons.Default.Settings, contentDescription = null)
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = if (selectedTimerMinutes > 0) 
                                stringResource(R.string.timer_minutes, selectedTimerMinutes)
                            else 
                                stringResource(R.string.timer_off),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        if (isMonitoring) {
                            stopSoundAnalysisService()
                        } else {
                            if (checkPermissions()) {
                                startSoundAnalysisService()
                            } else {
                                requestPermissions()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isMonitoring) 
                            stringResource(R.string.btn_stop) 
                        else 
                            stringResource(R.string.btn_start)
                    )
                }
            }
        }

        if (showDialog) {
            ModeSelectionDialog(onDismiss = { showDialog = false })
        }
    }

    @Composable
    fun ModeSelectionDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.select_modes_title)) },
            text = {
                Column {
                    Text(text = stringResource(R.string.masking_mode_title), style = MaterialTheme.typography.labelLarge)
                    allModeKeys.forEach { mode ->
                        val isChecked = selectedModes.contains(mode)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isChecked,
                                    onClick = {
                                        val newSet = selectedModes.toMutableSet()
                                        if (mode == "AUTO") {
                                            if (isChecked) {
                                                newSet.remove("AUTO")
                                                if (newSet.isEmpty()) newSet.add("DEEP_SPACE")
                                            } else {
                                                newSet.add("AUTO")
                                            }
                                        } else {
                                            if (isChecked) {
                                                newSet.remove(mode)
                                                if (newSet.isEmpty()) newSet.add("AUTO")
                                            } else {
                                                newSet.add(mode)
                                            }
                                        }
                                        selectedModes = newSet
                                    }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = isChecked, onCheckedChange = null)
                            Text(text = getModeDisplayName(mode), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(text = stringResource(R.string.timer_title), style = MaterialTheme.typography.labelLarge)
                    
                    // Continuous slider that reflects real-time progress while still allowing manual selection
                    Slider(
                        value = selectedTimerMinutes.toFloat(),
                        onValueChange = { 
                            // Manual snapping to timer options during user interaction
                            val snapped = timerOptions.minByOrNull { opt -> kotlin.math.abs(opt - it) } ?: 0
                            selectedTimerMinutes = snapped 
                        },
                        valueRange = 0f..120f,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        timerOptions.forEach { mins ->
                            // Highlight the label closest to the current timer value
                            val isClosest = mins == timerOptions.minByOrNull { kotlin.math.abs(it - selectedTimerMinutes) }
                            Text(
                                text = if (mins == 0) stringResource(R.string.off) else "${mins}m",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isClosest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    if (checkPermissions()) {
                        startSoundAnalysisService()
                    } else {
                        requestPermissions()
                    }
                }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    private fun getModeDisplayName(mode: String): String {
        return when (mode) {
            "AUTO" -> "Auto (AI)"
            else -> {
                try {
                    val noiseType = NoiseGenerator.NoiseType.valueOf(mode)
                    getString(noiseType.resId)
                } catch (e: Exception) {
                    mode
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isMonitoring = SoundAnalysisService.instance != null
        if (!isMonitoring) {
            detectedLabel = getString(R.string.status_idle)
            maskingSuggestion = getString(R.string.suggestion_default)
            repeat(magnitudes.size) { i -> magnitudes[i] = 0.1f }
        }
        ContextCompat.registerReceiver(
            this, updateReceiver,
            IntentFilter("SoundAnalysisUpdate"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(updateReceiver)
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startSoundAnalysisService() {
        val intent = Intent(this, SoundAnalysisService::class.java)
        intent.putExtra("EXTRA_MODES", selectedModes.toTypedArray())
        intent.putExtra("EXTRA_TIMER", selectedTimerMinutes)
        startForegroundService(intent)
        isMonitoring = true
        detectedLabel = getString(R.string.monitoring_active)
    }

    private fun stopSoundAnalysisService() {
        stopService(Intent(this, SoundAnalysisService::class.java))
        isMonitoring = false
        detectedLabel = getString(R.string.status_idle)
        maskingSuggestion = getString(R.string.suggestion_default)
        repeat(magnitudes.size) { i -> magnitudes[i] = 0.1f }
    }
}
