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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val modes = intent?.getStringArrayExtra("EXTRA_MODES")
        if (modes == null || modes.contains("AUTO")) {
            isAutoMode = true
        } else {
            isAutoMode = false
            manualModes = modes.mapNotNull { 
                try { NoiseGenerator.NoiseType.valueOf(it) } catch (e: Exception) { null }
            }.toSet()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startAnalysis() {
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

        val audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
        
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }

        try {
            audioRecord.startRecording()
        } catch (e: IllegalStateException) {
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
                
                val loadedSamples = audioData.load(audioRecord)
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

                val finalModes = if (isAutoMode) {
                    val suggestion = getNoiseTypeForLabel(label)
                    if (suggestion == null) {
                        setOf(NoiseGenerator.NoiseType.DEEP_SPACE)
                    } else {
                        setOf(suggestion)
                    }
                } else {
                    manualModes
                }
                
                noiseGenerator.setModes(finalModes)

                val modeNames = finalModes.joinToString(", ") { getString(it.resId) }
                val message = if (!isAutoMode) {
                    getString(R.string.manual_masking, modeNames)
                } else if (label.isNotEmpty()) {
                    getString(R.string.status_detected, label) + " - " + modeNames
                } else {
                    getString(R.string.ambient_masking, modeNames)
                }
                
                updateNotification(message)
                sendUpdateToUI(label, modeNames, amplitude, db.toInt())
            }
        }, 0, 1000)
    }

    private fun sendUpdateToUI(label: String, masking: String, amplitude: Float, db: Int) {
        val intent = Intent("SoundAnalysisUpdate").apply {
            setPackage(packageName)
            putExtra("label", label)
            putExtra("masking", masking)
            putExtra("amplitude", amplitude)
            putExtra("db", db)
        }
        sendBroadcast(intent)
    }

    private fun getNoiseTypeForLabel(label: String): NoiseGenerator.NoiseType? {
        val l = label.lowercase()
        return when {
            l.contains("speech") || l.contains("voice") || l.contains("conversation") || 
            l.contains("shouting") || l.contains("laughter") || l.contains("music") || 
            l.contains("singing") -> NoiseGenerator.NoiseType.STELLAR_WIND
            
            l.contains("tool") || l.contains("hammer") || l.contains("drill") || 
            l.contains("engine") || l.contains("vacuum") || l.contains("fan") || 
            l.contains("ac") -> NoiseGenerator.NoiseType.EARTH_RUMBLE
            
            l.contains("rain") || l.contains("liquid") -> NoiseGenerator.NoiseType.RAIN_FOREST
            
            l.contains("water") || l.contains("ocean") || l.contains("sea") || l.contains("wave") -> NoiseGenerator.NoiseType.OCEAN_WAVES

            l.contains("traffic") || l.contains("car") || l.contains("wind") || 
            l.contains("typing") || l.contains("keyboard") || l.contains("office") ||
            l.contains("clink") || l.contains("cup") -> NoiseGenerator.NoiseType.DEEP_SPACE

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
        timer?.cancel()
        audioClassifier?.close()
        noiseGenerator.stop()
    }
}
