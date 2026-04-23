package com.sonai.sonai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.sonai.sonai.audio.NoiseGenerator
import com.sonai.sonai.data.local.SonAIDatabase
import com.sonai.sonai.data.repository.SessionRepository
import com.sonai.sonai.service.GeofenceManager
import com.sonai.sonai.service.HealthConnectManager
import com.sonai.sonai.service.SoundAnalysisService
import com.google.android.gms.wearable.Wearable
import com.sonai.sonai.ui.components.WaveformComposable
import com.sonai.sonai.ui.settings.SettingsScreen
import com.sonai.sonai.ui.stats.StatsScreen
import com.sonai.sonai.ui.theme.SonAITheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    companion object {
        private const val DEFAULT_MAGNITUDE = 0.1f
        private const val MAGNITUDE_COUNT = 50
        private const val MAX_TIMER_MINUTES = 120f
    }

    private var isMonitoring by mutableStateOf(false)
    private var detectedLabel by mutableStateOf("")
    private var maskingSuggestion by mutableStateOf("")
    private var noiseLevel by mutableIntStateOf(0)
    private var heartRate by mutableIntStateOf(-1)
    private var selectedTimerMinutes by mutableIntStateOf(0)
    private var binauralMode by mutableStateOf(NoiseGenerator.BinauralType.OFF)
    private var autoDndEnabled by mutableStateOf(false)
    private var geofencingEnabled by mutableStateOf(false)
    private var showStats by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var companionEnabled by mutableStateOf(false)
    private var isWearInstalled by mutableStateOf(false)

    private lateinit var repository: SessionRepository
    private lateinit var geofenceManager: GeofenceManager
    private lateinit var healthConnectManager: HealthConnectManager

    private val magnitudes = mutableStateListOf<Float>().apply {
        repeat(MAGNITUDE_COUNT) { add(DEFAULT_MAGNITUDE) }
    }

    private val allModeKeys =
        arrayOf("AUTO", "DEEP_SPACE", "STELLAR_WIND", "EARTH_RUMBLE", "RAIN_FOREST", "OCEAN_WAVES")
    private val timerOptions = listOf(0, 15, 30, 45, 60, 90, 120)
    private var selectedModes by mutableStateOf(setOf("AUTO"))

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SoundAnalysisUpdate" -> {
                    detectedLabel = intent.getStringExtra("label") ?: ""
                    maskingSuggestion = intent.getStringExtra("masking") ?: ""
                    val amplitude =
                        intent.getFloatExtra("amplitude", DEFAULT_MAGNITUDE)
                    noiseLevel = intent.getIntExtra("db", 0)

                    val seconds = intent.getIntExtra("timer_seconds", -1)
                    if (seconds >= 0) {
                        selectedTimerMinutes = (seconds / 60f).roundToInt()
                    }

                    magnitudes.removeAt(0)
                    magnitudes.add(amplitude.coerceIn(DEFAULT_MAGNITUDE, 1.0f))
                }

                "HeartRateUpdate" -> {
                    heartRate = intent.getIntExtra("bpm", -1)
                }
            }
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

    private val requestHealthPermissionLauncher = registerForActivityResult(
        HealthConnectManager.requestPermissionActivityContract()
    ) { granted ->
        if (granted.containsAll(healthConnectManager.permissions)) {
            Toast.makeText(this, "Health Connect access granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = SonAIDatabase.getDatabase(this)
        repository = SessionRepository(db.sessionDao())
        geofenceManager = GeofenceManager(this)
        healthConnectManager = HealthConnectManager(this)
        checkWearableNodes()

        setContent {
            SonAITheme {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                when {
                    showStats -> StatsScreen(repository, onBack = { showStats = false })
                    showSettings -> SettingsScreen(
                        gpsEnabled = geofencingEnabled,
                        onGpsToggled = {
                            if (it) {
                                if (checkLocationPermissions()) {
                                    geofencingEnabled = true
                                } else {
                                    requestLocationPermissions()
                                }
                            } else {
                                geofencingEnabled = false
                            }
                        },
                        companionEnabled = companionEnabled,
                        onCompanionToggled = { companionEnabled = it },
                        isWearInstalled = isWearInstalled,
                        onHealthConnectClick = {
                            scope.launch {
                                if (!healthConnectManager.hasPermissions()) {
                                    requestHealthPermissionLauncher.launch(healthConnectManager.permissions)
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        R.string.health_connect_linked,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onBack = { showSettings = false }
                    )

                    else -> MainScreen()
                }
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
        val showDialog = remember { mutableStateOf(false) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                if (!healthConnectManager.hasPermissions()) {
                                    requestHealthPermissionLauncher.launch(healthConnectManager.permissions)
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        R.string.health_connect_linked,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = "Health Connect",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = { showStats = true }) {
                            Icon(Icons.Default.History, contentDescription = null)
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    }
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
                MonitoringStatus()

                WaveformComposable(magnitudes = magnitudes)

                ModeSelectionCard(onClick = { showDialog.value = true })

                Spacer(modifier = Modifier.weight(1f))

                StartStopButton()
            }
        }

        if (showDialog.value) {
            ModeSelectionDialog(onDismiss = { showDialog.value = false })
        }
    }

    @Composable
    private fun MonitoringStatus() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
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

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = stringResource(R.string.noise_level, noiseLevel))
                if (heartRate > 0) {
                    Text(
                        text = stringResource(R.string.heart_rate_display, heartRate),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (isMonitoring && selectedModes.contains("AUTO")) {
                Text(
                    text = stringResource(R.string.smart_volume_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    @Composable
    private fun ModeSelectionCard(onClick: () -> Unit) {
        OutlinedCard(
            onClick = onClick,
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
    }

    @Composable
    private fun StartStopButton() {
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

    @Composable
    fun ModeSelectionDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.select_modes_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.masking_mode_title),
                        style = MaterialTheme.typography.labelLarge
                    )
                    ModeList()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.binaural_title),
                        style = MaterialTheme.typography.labelLarge
                    )
                    BinauralSelection()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.timer_title),
                        style = MaterialTheme.typography.labelLarge
                    )
                    TimerSelection()
                    Spacer(modifier = Modifier.height(16.dp))
                    AutomationToggles()
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

    @Composable
    private fun ModeList() {
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
                                toggleAutoMode(newSet, isChecked)
                            } else {
                                toggleManualMode(newSet, mode, isChecked)
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
    }

    private fun toggleAutoMode(newSet: MutableSet<String>, isChecked: Boolean) {
        if (isChecked) {
            newSet.remove("AUTO")
            if (newSet.isEmpty()) newSet.add("DEEP_SPACE")
        } else {
            newSet.add("AUTO")
        }
    }

    private fun toggleManualMode(newSet: MutableSet<String>, mode: String, isChecked: Boolean) {
        if (isChecked) {
            newSet.remove(mode)
            if (newSet.isEmpty()) newSet.add("AUTO")
        } else {
            newSet.add(mode)
        }
    }

    @Composable
    private fun TimerSelection() {
        Column {
            Text(
                text = stringResource(R.string.timer_title),
                style = MaterialTheme.typography.labelLarge
            )

            Slider(
                value = selectedTimerMinutes.toFloat(),
                onValueChange = {
                    val snapped = timerOptions.minByOrNull { opt -> kotlin.math.abs(opt - it) } ?: 0
                    selectedTimerMinutes = snapped
                },
                valueRange = 0f..MAX_TIMER_MINUTES,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                timerOptions.forEach { mins ->
                    val isClosest =
                        mins == timerOptions.minByOrNull { kotlin.math.abs(it - selectedTimerMinutes) }
                    Text(
                        text = if (mins == 0) stringResource(R.string.off) else "${mins}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isClosest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    private fun BinauralSelection() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            NoiseGenerator.BinauralType.entries.forEach { type ->
                FilterChip(
                    selected = binauralMode == type,
                    onClick = { binauralMode = type },
                    label = { Text(type.name, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }

    @Composable
    private fun AutomationToggles() {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dnd_title), modifier = Modifier.weight(1f))
                Switch(checked = autoDndEnabled, onCheckedChange = { autoDndEnabled = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.geofencing_title), modifier = Modifier.weight(1f))
                Switch(
                    checked = geofencingEnabled,
                    onCheckedChange = {
                        if (it) {
                            if (checkLocationPermissions()) {
                                geofencingEnabled = true
                            } else {
                                requestLocationPermissions()
                            }
                        } else {
                            geofencingEnabled = false
                        }
                    }
                )
            }
        }
    }

    private fun checkLocationPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val backgroundLocation =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        return fineLocation && backgroundLocation
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Background location must be requested separately on some Android versions or in sequence
                requestBackgroundLocationPermission()
            } else {
                geofencingEnabled = true
            }
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            geofencingEnabled = false
        }
    }

    private fun requestLocationPermissions() {
        requestLocationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private val requestBackgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            geofencingEnabled = true
        } else {
            Toast.makeText(this, "Background location permission denied", Toast.LENGTH_SHORT).show()
            geofencingEnabled = false
        }
    }

    private fun requestBackgroundLocationPermission() {
        requestBackgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun getModeDisplayName(mode: String): String {
        return when (mode) {
            "AUTO" -> "Auto (AI)"
            else -> {
                try {
                    val noiseType = NoiseGenerator.NoiseType.valueOf(mode)
                    getString(noiseType.resId)
                } catch (_: IllegalArgumentException) {
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
            repeat(magnitudes.size) { i -> magnitudes[i] = DEFAULT_MAGNITUDE }
        }
        val filter = IntentFilter().apply {
            addAction("SoundAnalysisUpdate")
            addAction("HeartRateUpdate")
        }
        ContextCompat.registerReceiver(
            this, updateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(updateReceiver)
    }

    private fun checkPermissions(): Boolean {
        val permissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        requestPermissionLauncher.launch(permissions)
    }

    private fun startSoundAnalysisService() {
        val intent = Intent(this, SoundAnalysisService::class.java)
        intent.putExtra("EXTRA_MODES", selectedModes.toTypedArray())
        intent.putExtra("EXTRA_TIMER", selectedTimerMinutes)
        intent.putExtra("EXTRA_BINAURAL", binauralMode.name)
        intent.putExtra("EXTRA_DND", autoDndEnabled)
        intent.putExtra("EXTRA_COMPANION", companionEnabled)
        startForegroundService(intent)
        isMonitoring = true
        detectedLabel = getString(R.string.monitoring_active)
    }

    private fun stopSoundAnalysisService() {
        stopService(Intent(this, SoundAnalysisService::class.java))
        isMonitoring = false
        detectedLabel = getString(R.string.status_idle)
        maskingSuggestion = getString(R.string.suggestion_default)
        repeat(magnitudes.size) { i -> magnitudes[i] = DEFAULT_MAGNITUDE }
    }

    private fun checkWearableNodes() {
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            isWearInstalled = nodes.isNotEmpty()
        }
    }
}
