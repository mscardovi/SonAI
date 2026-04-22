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

class MainActivity : ComponentActivity() {

    private var isMonitoring by mutableStateOf(false)
    private var detectedLabel by mutableStateOf("")
    private var maskingSuggestion by mutableStateOf("")
    private var noiseLevel by mutableIntStateOf(0)
    private val magnitudes = mutableStateListOf<Float>().apply {
        repeat(30) { add(0.1f) }
    }
    
    private val allModeKeys = arrayOf("AUTO", "DEEP_SPACE", "STELLAR_WIND", "EARTH_RUMBLE", "RAIN_FOREST", "OCEAN_WAVES")
    private var selectedModes by mutableStateOf(setOf("AUTO"))

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            detectedLabel = intent?.getStringExtra("label") ?: ""
            maskingSuggestion = intent?.getStringExtra("masking") ?: ""
            val amplitude = intent?.getFloatExtra("amplitude", 0.1f) ?: 0.1f
            noiseLevel = intent?.getIntExtra("db", 0) ?: 0

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

                WaveformComposable(magnitudes = magnitudes)

                OutlinedCard(
                    onClick = { showDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
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
                                            newSet.clear()
                                            newSet.add("AUTO")
                                        } else {
                                            newSet.remove("AUTO")
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
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = null // Handled by Row selectable
                            )
                            Text(
                                text = getModeDisplayName(mode),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    // Always start or update service when modes are confirmed
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
            "AUTO" -> getString(R.string.mode_auto)
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
