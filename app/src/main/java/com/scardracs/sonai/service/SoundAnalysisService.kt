package com.scardracs.sonai.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.util.Log
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import com.scardracs.sonai.MainActivity
import com.scardracs.sonai.R
import com.scardracs.sonai.audio.NoiseGenerator
import java.util.Timer
import java.util.TimerTask
import kotlin.math.log10
import kotlin.math.sqrt

class SoundAnalysisService : LifecycleService() {

    private var audioClassifier: AudioClassifier? = null
    private var timer: Timer? = null
    private lateinit var noiseGenerator: NoiseGenerator
    
    private var isAutoMode = true
    private var manualModes = setOf<NoiseGenerator.NoiseType>()
    
    // Smooth weights for Auto mode transitions
    private val smoothedWeights = NoiseGenerator.NoiseType.entries.associateWith { 0f }.toMutableMap()
    private val smoothingFactor = 0.2f // Higher = faster, Lower = smoother

    companion object {
        private const val TAG = "SoundAnalysisService"
        private const val CHANNEL_ID = "SoundAnalysisChannel"
        private const val NOTIFICATION_ID = 1
        private const val MODEL_FILE = "yamnet.tflite"
        private const val ACTION_STOP = "com.scardracs.sonai.ACTION_STOP"

        var instance: SoundAnalysisService? = null
            private set
        
        private val IGNORED_LABELS = setOf(
            "White noise", "Pink noise", "Static", "Hiss", "Hum", "Noise"
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
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
                .setScoreThreshold(0.1f)
                .setMaxResults(5)
                .build()
            
            audioClassifier = AudioClassifier.createFromOptions(this, options)
            startAnalysis()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe AudioClassifier", e)
            updateNotification(getString(R.string.error_msg, e.localizedMessage))
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
        
        val modes = intent?.getStringArrayExtra("EXTRA_MODES")
        val timerMinutes = intent?.getIntExtra("EXTRA_TIMER", -1) ?: -1
        
        if (timerMinutes > 0) {
            startTimer(timerMinutes)
        } else if (timerMinutes == 0) {
            stopTimer()
        }

        if (modes != null) {
            if (modes.contains("AUTO")) {
                isAutoMode = true
            } else {
                isAutoMode = false
                manualModes = modes.mapNotNull { 
                    try { NoiseGenerator.NoiseType.valueOf(it) } catch (e: Exception) { null }
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

        return super.onStartCommand(intent, flags, startId)
    }

    private fun startTimer(minutes: Int) {
        remainingSeconds = minutes * 60
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
        }, 1000, 1000)
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
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        
        // Update UI to show manual masking status
        val modeNames = manualModes.joinToString(", ") { getString(it.resId) }
        updateNotification(getString(R.string.manual_masking, modeNames))
        sendUpdateToUI("", modeNames, 0.1f, 0, remainingSeconds)
    }

    private fun startAnalysis() {
        if (isAnalysisRunning) return
        isAnalysisRunning = true
        
        val classifier = audioClassifier ?: return
        val sampleRate = 16000 
        val sampleCount = 16000
        
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSizeInBytes = maxOf(minBufferSize, sampleCount * 2)
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission RECORD_AUDIO not granted")
            return
        }

        val record = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val attributionContext = createAttributionContext("sound_analysis")
            AudioRecord.Builder()
                .setAudioSource(AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setContext(attributionContext)
                .build()
        } else {
            AudioRecord(
                AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes
            )
        }
        
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            isAnalysisRunning = false
            return
        }

        audioRecord = record
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            isAnalysisRunning = false
            return
        }

        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                val audioData = AudioData.create(
                    AudioData.AudioDataFormat.builder()
                        .setNumOfChannels(1)
                        .setSampleRate(sampleRate.toFloat())
                        .build(),
                    sampleCount
                )
                
                val loadedSamples = audioData.load(record)
                if (loadedSamples <= 0) return

                // Calculate dB Level from the internal float array
                val floatArray = audioData.buffer
                var sum = 0.0
                for (sample in floatArray) {
                    sum += (sample.toDouble() * sample.toDouble())
                }
                val rms = if (floatArray.size > 0) sqrt(sum / floatArray.size) else 0.0
                val db = if (rms > 0) 20 * log10(rms) + 90 else 0.0

                val results = classifier.classify(audioData)
                val allCategories = results.classificationResults().firstOrNull()?.classifications()?.firstOrNull()?.categories()
                
                val topResult = allCategories
                    ?.filter { it.score() > 0.15f }
                    ?.filterNot { IGNORED_LABELS.contains(it.categoryName()) }
                    ?.maxByOrNull { it.score() }

                val label = topResult?.categoryName() ?: ""
                val amplitude = topResult?.score() ?: (rms.toFloat() * 10f).coerceIn(0.1f, 1.0f)
                
                Log.d(TAG, "Analysis result - Label: '$label', RMS: $rms, dB: $db")

                // Adaptive Volume Logic: 
                // Map dB (range ~35 to ~75) to a master volume multiplier (0.4 to 1.0)
                if (isAutoMode) {
                    val adaptiveVol = ((db - 35) / 40.0).coerceIn(0.4, 1.0).toFloat()
                    noiseGenerator.setMasterVolume(adaptiveVol)
                } else {
                    noiseGenerator.setMasterVolume(0.7f) // Consistent volume for manual mode
                }

                val finalModes = if (isAutoMode) {
                    val suggestion = getNoiseTypeForLabel(label)
                    
                    // Target weights based on current detection
                    val targets = mutableMapOf(
                        NoiseGenerator.NoiseType.DEEP_SPACE to 0.15f,
                        NoiseGenerator.NoiseType.STELLAR_WIND to 0.05f,
                        NoiseGenerator.NoiseType.EARTH_RUMBLE to 0.05f,
                        NoiseGenerator.NoiseType.RAIN_FOREST to 0.05f,
                        NoiseGenerator.NoiseType.OCEAN_WAVES to 0.05f
                    )
                    
                    if (suggestion != null) {
                        targets[suggestion] = 0.9f
                    } else {
                        targets[NoiseGenerator.NoiseType.DEEP_SPACE] = 0.35f
                    }

                    // Apply EMA smoothing to the weights
                    NoiseGenerator.NoiseType.entries.forEach { type ->
                        val current = smoothedWeights[type] ?: 0f
                        val target = targets[type] ?: 0f
                        smoothedWeights[type] = (target * smoothingFactor) + (current * (1f - smoothingFactor))
                    }
                    
                    noiseGenerator.setWeightedModes(smoothedWeights)
                    
                    // Show dominant modes in UI based on smoothed values
                    smoothedWeights.filter { it.value > 0.3f }.keys.ifEmpty { setOf(NoiseGenerator.NoiseType.DEEP_SPACE) }
                } else {
                    noiseGenerator.setModes(manualModes)
                    manualModes
                }

                val modeNames = finalModes.joinToString(", ") { getString(it.resId) }
                val message = if (!isAutoMode) {
                    getString(R.string.manual_masking, modeNames)
                } else if (label.isNotEmpty()) {
                    getString(R.string.status_detected, label) + " - " + modeNames
                } else {
                    getString(R.string.ambient_masking, modeNames)
                }
                
                updateNotification(message)
                sendUpdateToUI(label, modeNames, amplitude, db.toInt(), remainingSeconds, isAutoMode)
            }
        }, 0, 1000)
    }

    private fun sendUpdateToUI(label: String, masking: String, amplitude: Float, db: Int, timerSeconds: Int = -1, isAuto: Boolean = true) {
        val intent = Intent("SoundAnalysisUpdate").apply {
            setPackage(packageName)
            putExtra("label", label)
            putExtra("masking", masking)
            putExtra("amplitude", amplitude)
            putExtra("db", db)
            putExtra("timer_seconds", timerSeconds)
            putExtra("is_auto", isAuto)
        }
        sendBroadcast(intent)
    }

    private fun getNoiseTypeForLabel(label: String): NoiseGenerator.NoiseType? {
        val l = label.lowercase()
        return when {
            // High frequency / Voice / Melodic -> Stellar Wind (Pinkish/Whistle)
            l.contains("speech") || l.contains("voice") || l.contains("conversation") || 
            l.contains("shouting") || l.contains("laughter") || l.contains("music") || 
            l.contains("singing") || l.contains("bird") || l.contains("whistle") -> NoiseGenerator.NoiseType.STELLAR_WIND
            
            // Low frequency / Mechanical / Deep -> Earth Rumble (Brown/Rumble)
            l.contains("tool") || l.contains("hammer") || l.contains("drill") || 
            l.contains("engine") || l.contains("vacuum") || l.contains("fan") || 
            l.contains("ac") || l.contains("heavy") || l.contains("truck") || 
            l.contains("explosion") || l.contains("thunder") -> NoiseGenerator.NoiseType.EARTH_RUMBLE
            
            // Rain / Splashing / Forest -> Rain Forest
            l.contains("rain") || l.contains("liquid") || l.contains("drip") || 
            l.contains("stream") || l.contains("river") || l.contains("forest") ||
            l.contains("cricket") || l.contains("insect") -> NoiseGenerator.NoiseType.RAIN_FOREST
            
            // Waves / Large water / Coastal -> Ocean Waves
            l.contains("ocean") || l.contains("sea") || l.contains("wave") || 
            l.contains("beach") || l.contains("surf") -> NoiseGenerator.NoiseType.OCEAN_WAVES

            // Urban / Background / Static -> Deep Space (White/Hum)
            l.contains("traffic") || l.contains("car") || l.contains("wind") || 
            l.contains("typing") || l.contains("keyboard") || l.contains("office") ||
            l.contains("clink") || l.contains("cup") || l.contains("waterfall") ||
            l.contains("water") || l.contains("whoosh") -> NoiseGenerator.NoiseType.DEEP_SPACE

            else -> null
        }
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
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_stop), stopPendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopAnalysis()
        audioClassifier?.close()
        noiseGenerator.stop()
    }
}
