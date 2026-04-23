package com.sonai.sonai.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import com.sonai.sonai.MainActivity
import com.sonai.sonai.R
import com.sonai.sonai.audio.NoiseGenerator
import com.sonai.sonai.data.local.SonAIDatabase
import com.sonai.sonai.data.local.entity.NoiseEvent
import com.sonai.sonai.data.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask
import kotlin.math.log10
import kotlin.math.sqrt

class SoundAnalysisService : LifecycleService() {

    data class AnalysisUpdate(
        val label: String,
        val masking: String,
        val amplitude: Float,
        val db: Int,
        val timerSeconds: Int = -1,
        val isAuto: Boolean = true
    )

    private var audioClassifier: AudioClassifier? = null
    private var timer: Timer? = null
    private lateinit var noiseGenerator: NoiseGenerator

    private var isAutoMode = true
    private var manualModes = setOf<NoiseGenerator.NoiseType>()
    private var isCompanionEnabled = false
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: SessionRepository
    private var currentSessionId: Long = -1
    private val dndManager by lazy { DndManager(this) }
    
    private var totalNoiseForAvg = 0.0
    private var noiseSampleCount = 0
    private var userIgnoredLabels = mutableSetOf<String>()

    private var currentHeartRate = -1

    private val heartRateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            currentHeartRate = intent?.getIntExtra("bpm", -1) ?: -1
            Log.d(TAG, "Service received heart rate: $currentHeartRate")
        }
    }

    // Smooth weights for Auto mode transitions
    private val smoothedWeights =
        NoiseGenerator.NoiseType.entries.associateWith { 0f }.toMutableMap()
    private val smoothingFactor = 0.2f // Higher = faster, Lower = smoother

    companion object {
        private const val TAG = "SoundAnalysisService"
        private const val CHANNEL_ID = "SoundAnalysisChannel"
        private const val NOTIFICATION_ID = 1
        private const val MODEL_FILE = "yamnet.tflite"
        private const val ACTION_STOP = "com.sonai.sonai.ACTION_STOP"

        private const val SAMPLE_RATE = 16000
        private const val SAMPLE_COUNT = 16000
        private const val DB_OFFSET = 90.0
        private const val DB_THRESHOLD_LOW = 35.0
        private const val DB_RANGE = 40.0
        private const val VOL_MIN = 0.4f
        private const val VOL_MAX = 1.0f
        private const val VOL_MANUAL = 0.7f
        private const val SCORE_THRESHOLD = 0.15f
        private const val SECONDS_IN_MINUTE = 60
        private const val TARGET_DETECTED = 0.9f
        private const val TARGET_DEFAULT = 0.35f
        private const val TARGET_BACKGROUND = 0.15f
        private const val TARGET_MIN = 0.05f
        private const val AUTO_MODE_THRESHOLD = 0.3f
        private const val DEFAULT_AMPLITUDE = 0.1f
        private const val BINAURAL_VOLUME = 0.4f
        private const val RMS_MULTIPLIER = 10f
        private const val TIMER_INTERVAL = 1000L

        var instance: SoundAnalysisService? = null
            private set

        private val IGNORED_LABELS = setOf(
            "White noise", "Pink noise", "Static", "Hiss", "Hum", "Noise"
        )

        internal fun getNoiseTypeForLabel(label: String): NoiseGenerator.NoiseType? {
            val l = label.lowercase()
            val typeMappings = mapOf(
                NoiseGenerator.NoiseType.STELLAR_WIND to listOf(
                    "speech", "voice", "conversation", "shouting", "laughter", "music", "singing", "bird", "whistle"
                ),
                NoiseGenerator.NoiseType.EARTH_RUMBLE to listOf(
                    "tool", "hammer", "drill", "engine", "vacuum", "fan", "air conditioning", "heavy", "truck", "explosion", "thunder"
                ),
                NoiseGenerator.NoiseType.RAIN_FOREST to listOf(
                    "rain", "liquid", "drip", "stream", "river", "forest", "cricket", "insect", "spray"
                ),
                NoiseGenerator.NoiseType.OCEAN_WAVES to listOf(
                    "ocean", "sea", "wave", "beach", "surf"
                ),
                NoiseGenerator.NoiseType.DEEP_SPACE to listOf(
                    "traffic", "car", "wind", "typing", "keyboard", "office", "clink", "cup", "waterfall", "water", "whoosh", "steam"
                )
            )

            return typeMappings.entries.firstOrNull { (_, keywords) ->
                keywords.any { l.contains(it) }
            }?.key
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val db = SonAIDatabase.getDatabase(this)
        repository = SessionRepository(db.sessionDao())
        
        createNotificationChannel()
        noiseGenerator = NoiseGenerator()

        val notification = createNotification(getString(R.string.initializing))
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .build()

            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(baseOptions)
                .setScoreThreshold(DEFAULT_AMPLITUDE)
                .setMaxResults(5)
                .build()

            audioClassifier = AudioClassifier.createFromOptions(this, options)
            startAnalysis()
            
            ContextCompat.registerReceiver(
                this, heartRateReceiver,
                IntentFilter("HeartRateUpdate"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to initialize MediaPipe AudioClassifier", e)
            updateNotification(getString(R.string.error_msg, e.localizedMessage ?: "Unknown error"))
        }
    }

    private var audioRecord: AudioRecord? = null
    private var isAnalysisRunning = false

    private var remainingSeconds = -1
    private var timerCountdown: Timer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        isCompanionEnabled = intent?.getBooleanExtra("EXTRA_COMPANION", false) ?: false
        if (isCompanionEnabled) {
            serviceScope.launch {
                WearCommunicationManager(this@SoundAnalysisService).sendStatus("PLAYING")
            }
        }

        handleTimerAndDnd(intent)
        handleBinauralBeat(intent)
        handleNoiseModes(intent)

        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleTimerAndDnd(intent: Intent?) {
        val timerMinutes = intent?.getIntExtra("EXTRA_TIMER", -1) ?: -1
        val useDnd = intent?.getBooleanExtra("EXTRA_DND", false) ?: false

        if (timerMinutes > 0) {
            startTimer(timerMinutes)
            startSessionInDb(timerMinutes)
            if (useDnd) dndManager.setDndMode(true)
        } else if (timerMinutes == 0) {
            stopTimer()
            dndManager.setDndMode(false)
        }
    }

    private fun handleBinauralBeat(intent: Intent?) {
        val binauralName = intent?.getStringExtra("EXTRA_BINAURAL")
        binauralName?.let {
            try {
                val type = NoiseGenerator.BinauralType.valueOf(it)
                noiseGenerator.setBinauralBeat(type, BINAURAL_VOLUME)
            } catch (_: Exception) {
            }
        }
    }

    private fun handleNoiseModes(intent: Intent?) {
        val modes = intent?.getStringArrayExtra("EXTRA_MODES")
        if (modes != null) {
            if (modes.contains("AUTO")) {
                isAutoMode = true
            } else {
                isAutoMode = false
                manualModes = modes.mapNotNull {
                    try {
                        NoiseGenerator.NoiseType.valueOf(it)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }.toSet()
                noiseGenerator.setModes(manualModes)
            }
        }

        // Dynamically start/stop analysis based on mode change
        if (isAutoMode && !isAnalysisRunning) {
            startAnalysis()
        } else if (!isAutoMode && isAnalysisRunning) {
            stopAnalysis()
        }
    }

    private fun startTimer(minutes: Int) {
        remainingSeconds = minutes * SECONDS_IN_MINUTE
        timerCountdown?.cancel()
        timerCountdown = Timer()
        timerCountdown?.schedule(object : TimerTask() {
            override fun run() {
                if (remainingSeconds > 0) {
                    remainingSeconds--
                } else {
                    stopSelf()
                }
            }
        }, TIMER_INTERVAL, TIMER_INTERVAL)
    }

    private fun stopTimer() {
        remainingSeconds = -1
        timerCountdown?.cancel()
        timerCountdown = null
    }

    private fun stopAnalysis() {
        isAnalysisRunning = false
        timer?.cancel()
        timer = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: IllegalStateException) {
            // Expected if already stopped
        }
        audioRecord = null

        // Update UI to show manual masking status
        val modeNames = manualModes.joinToString(", ") { getString(it.resId) }
        updateNotification(getString(R.string.manual_masking, modeNames))
        sendUpdateToUI(AnalysisUpdate("", modeNames, DEFAULT_AMPLITUDE, 0, remainingSeconds))
    }

    private fun startAnalysis() {
        if (isAnalysisRunning) return
        val classifier = audioClassifier ?: return

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Permission RECORD_AUDIO not granted")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSizeInBytes = maxOf(minBufferSize, SAMPLE_COUNT * 2)

        val record = createAudioRecord(bufferSizeInBytes)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }

        audioRecord = record
        try {
            record.startRecording()
            isAnalysisRunning = true
        } catch (_: IllegalStateException) {
            return
        }

        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                processAudioData(classifier, record)
            }
        }, 0, TIMER_INTERVAL)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(bufferSizeInBytes: Int): AudioRecord {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val attributionContext = createAttributionContext("sound_analysis")
            AudioRecord.Builder()
                .setAudioSource(AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setContext(attributionContext)
                .build()
        } else {
            AudioRecord(
                AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes
            )
        }
    }

    private fun startSessionInDb(minutes: Int) {
        serviceScope.launch {
            currentSessionId = repository.startSession(System.currentTimeMillis(), minutes)
            totalNoiseForAvg = 0.0
            noiseSampleCount = 0
        }
    }

    private fun processAudioData(classifier: AudioClassifier, record: AudioRecord) {
        val audioData = AudioData.create(
            AudioData.AudioDataFormat.builder()
                .setNumOfChannels(1)
                .setSampleRate(SAMPLE_RATE.toFloat())
                .build(),
            SAMPLE_COUNT
        )

        val loadedSamples = audioData.load(record)
        if (loadedSamples <= 0) return

        val floatArray = audioData.buffer
        val rms = calculateRMS(floatArray)
        val db = if (rms > 0) 20 * log10(rms) + DB_OFFSET else 0.0

        val results = classifier.classify(audioData)
        val allCategories = results.classificationResults().firstOrNull()
            ?.classifications()?.firstOrNull()?.categories()

        val topResult = allCategories
            ?.filter { it.score() > SCORE_THRESHOLD }
            ?.filterNot { IGNORED_LABELS.contains(it.categoryName()) }
            ?.maxByOrNull { it.score() }

        val label = topResult?.categoryName() ?: ""
        val amplitude = topResult?.score() ?: (rms.toFloat() * RMS_MULTIPLIER).coerceIn(DEFAULT_AMPLITUDE, 1.0f)

        if (label.isNotEmpty() && !userIgnoredLabels.contains(label)) {
            recordNoiseEvent(label, db.toInt())
        }
        
        totalNoiseForAvg += db
        noiseSampleCount++

        updateAudioEngine(label, db, amplitude)
    }

    private fun recordNoiseEvent(label: String, db: Int) {
        if (currentSessionId == -1L) return
        serviceScope.launch {
            repository.addNoiseEvent(
                NoiseEvent(
                    sessionId = currentSessionId,
                    timestamp = System.currentTimeMillis(),
                    label = label,
                    dbLevel = db
                )
            )
        }
    }

    private fun calculateRMS(floatArray: FloatArray): Double {
        var sum = 0.0
        for (sample in floatArray) {
            sum += (sample.toDouble() * sample.toDouble())
        }
        return if (floatArray.isNotEmpty()) sqrt(sum / floatArray.size) else 0.0
    }

    private fun updateAudioEngine(label: String, db: Double, amplitude: Float) {
        if (isAutoMode) {
            val adaptiveVol = ((db - DB_THRESHOLD_LOW) / DB_RANGE).coerceIn(
                VOL_MIN.toDouble(),
                VOL_MAX.toDouble()
            ).toFloat()
            noiseGenerator.setMasterVolume(adaptiveVol)
        } else {
            noiseGenerator.setMasterVolume(VOL_MANUAL)
        }

        val finalModes = if (isAutoMode) {
            applyAutoModes(label)
        } else {
            noiseGenerator.setModes(manualModes)
            manualModes
        }

        val modeNames = finalModes.joinToString(", ") { getString(it.resId) }
        val message = when {
            !isAutoMode -> getString(R.string.manual_masking, modeNames)
            label.isNotEmpty() -> getString(R.string.status_detected, label) + " - " + modeNames
            else -> getString(R.string.ambient_masking, modeNames)
        }

        updateNotification(message)
        sendUpdateToUI(
            AnalysisUpdate(
                label,
                modeNames,
                amplitude,
                db.toInt(),
                remainingSeconds,
                isAutoMode
            )
        )

        // Sync state to Wear periodically during analysis
        if (isCompanionEnabled) {
            serviceScope.launch {
                WearCommunicationManager(this@SoundAnalysisService).sendStatus("PLAYING")
            }
        }
    }

    private fun applyAutoModes(label: String): Set<NoiseGenerator.NoiseType> {
        val suggestion = getNoiseTypeForLabel(label)
        val targets = NoiseGenerator.NoiseType.entries.associateWith { TARGET_MIN }.toMutableMap()
        targets[NoiseGenerator.NoiseType.DEEP_SPACE] = TARGET_BACKGROUND

        if (suggestion != null) {
            targets[suggestion] = TARGET_DETECTED
        } else {
            targets[NoiseGenerator.NoiseType.DEEP_SPACE] = TARGET_DEFAULT
        }

        NoiseGenerator.NoiseType.entries.forEach { type ->
            val current = smoothedWeights[type] ?: 0f
            val target = targets[type] ?: 0f
            smoothedWeights[type] = (target * smoothingFactor) + (current * (1f - smoothingFactor))
        }

        noiseGenerator.setWeightedModes(smoothedWeights)
        return smoothedWeights.filter { it.value > AUTO_MODE_THRESHOLD }.keys.ifEmpty { setOf(NoiseGenerator.NoiseType.DEEP_SPACE) }
    }

    private fun sendUpdateToUI(update: AnalysisUpdate) {
        val intent = Intent("SoundAnalysisUpdate").apply {
            setPackage(packageName)
            putExtra("label", update.label)
            putExtra("masking", update.masking)
            putExtra("amplitude", update.amplitude)
            putExtra("db", update.db)
            putExtra("timer_seconds", update.timerSeconds)
            putExtra("is_auto", update.isAuto)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Sound Analysis Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SoundAnalysisService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SonAI Focus Zone")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.btn_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Sync state to Wear before destroying
        if (isCompanionEnabled) {
            serviceScope.launch {
                WearCommunicationManager(this@SoundAnalysisService).sendStatus("IDLE")
            }
        }

        instance = null
        
        try {
            unregisterReceiver(heartRateReceiver)
        } catch (_: Exception) {}
        
        if (currentSessionId != -1L) {
            val avg = if (noiseSampleCount > 0) totalNoiseForAvg / noiseSampleCount else 0.0
            val focusIndex = calculateFocusIndex(avg, noiseSampleCount)
            serviceScope.launch {
                repository.endSession(currentSessionId, System.currentTimeMillis(), avg, focusIndex)
            }
        }
        dndManager.setDndMode(false)
        
        stopAnalysis()
        audioClassifier?.close()
        noiseGenerator.stop()
    }

    private fun calculateFocusIndex(avgDb: Double, count: Int): Int {
        if (count == 0) return 100
        val base = 100.0
        val penalty = (avgDb - 40.0).coerceAtLeast(0.0) * 1.5
        return (base - penalty).toInt().coerceIn(0, 100)
    }
}
