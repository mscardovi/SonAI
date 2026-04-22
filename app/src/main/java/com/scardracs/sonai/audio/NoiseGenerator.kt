package com.scardracs.sonai.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.scardracs.sonai.R
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sin
import kotlin.random.Random

class NoiseGenerator {

    private companion object {
        private const val TAG = "NoiseGenerator"
        private const val SAMPLE_RATE = 44100
        private const val FADE_STEP = 0.02f
        private const val MAX_TOTAL_VOLUME = 0.5f
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var thread: Thread? = null
    
    // Manage multiple active noise types and their volumes
    private val activeVolumes = mutableMapOf<NoiseType, Float>()
    private var targetTypes = setOf<NoiseType>()
    
    private val lock = ReentrantLock()

    enum class NoiseType(val resId: Int) {
        DEEP_SPACE(R.string.mode_white),
        STELLAR_WIND(R.string.mode_pink),
        EARTH_RUMBLE(R.string.mode_brown),
        RAIN_FOREST(R.string.mode_rain),
        OCEAN_WAVES(R.string.mode_ocean)
    }

    fun setModes(types: Set<NoiseType>) {
        lock.withLock {
            targetTypes = types
            if (!isPlaying && types.isNotEmpty()) {
                isPlaying = true
                startThread()
            }
        }
    }

    private fun startThread() {
        thread = Thread { runGenerationLoop() }.apply { start() }
    }

    private fun runGenerationLoop() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
            lock.withLock { isPlaying = false }
            return
        }

        lock.withLock { audioTrack = track }
        track.play()

        val samples = ShortArray(bufferSize / 2)
        val b = FloatArray(7) // Pink noise filters
        var lastOutBrown = 0f
        var phase1 = 0f; var phase2 = 0f; var phase3 = 0f; var phase4 = 0f
        
        // Low-pass state for "Natural" White noise
        var lastWhite = 0f
        
        // Multi-tonal modulation phases
        var tonePhase1 = 0f; var tonePhase2 = 0f; var tonePhase3 = 0f
        var windPhase = 0f
        
        // Thunder state
        var thunderLevel = 0f
        var nextThunderSample = (SAMPLE_RATE * 5 + Random.nextInt(SAMPLE_RATE * 15))
        var sampleCount = 0

        // Sea state
        var seaGullLevel = 0f
        var nextSeaGullSample = (SAMPLE_RATE * 8 + Random.nextInt(SAMPLE_RATE * 20))

        while (true) {
            val currentTargets = lock.withLock { targetTypes }
            val localIsPlaying = lock.withLock { isPlaying }

            // Update volumes for each type (fading)
            var activeAny = false
            NoiseType.entries.forEach { type ->
                val currentVol = activeVolumes.getOrDefault(type, 0f)
                val targetVol = if (currentTargets.contains(type)) 1f else 0f
                
                if (currentVol != targetVol) {
                    val nextVol = if (targetVol > currentVol) {
                        (currentVol + FADE_STEP).coerceAtMost(targetVol)
                    } else {
                        (currentVol - FADE_STEP).coerceAtLeast(targetVol)
                    }
                    activeVolumes[type] = nextVol
                }
                if ((activeVolumes[type] ?: 0f) > 0f) activeAny = true
            }

            if (!localIsPlaying && !activeAny) break

            for (i in samples.indices) {
                val rawWhite = Random.nextFloat() * 2 - 1
                
                // Organic LFOs (prime-relative speeds to avoid patterns)
                phase1 = (phase1 + 0.000021f) % 6.2831855f
                phase2 = (phase2 + 0.000073f) % 6.2831855f
                phase3 = (phase3 + 0.000137f) % 6.2831855f
                phase4 = (phase4 + 0.000511f) % 6.2831855f
                windPhase = (windPhase + 0.000011f) % 6.2831855f
                
                // Tonal modulation (extremely slow)
                tonePhase1 = (tonePhase1 + 0.000005f) % 6.2831855f
                tonePhase2 = (tonePhase2 + 0.000003f) % 6.2831855f
                tonePhase3 = (tonePhase3 + 0.000001f) % 6.2831855f
                
                val swell = (sin(phase1) * 0.2f + sin(phase2) * 0.1f + 0.7f)
                val windSigh = (sin(windPhase) * 0.15f + 0.85f)
                val timberMod = sin(tonePhase1) * 0.12f // Increased timbral shift

                // 1. Natural White (modulating low-pass to vary "softness")
                val whiteLP = 0.82f + timberMod
                lastWhite = lastWhite * whiteLP + rawWhite * (1f - whiteLP)
                val whiteOut = lastWhite * 0.8f * (sin(phase3) * 0.1f + 0.9f) * windSigh
                
                // 2. Pink with Ocean-like Swell and resonance modulation
                val pinkRes = 0.5362f + sin(tonePhase2) * 0.05f
                b[0] = 0.99886f * b[0] + rawWhite * 0.0555179f
                b[1] = 0.99332f * b[1] + rawWhite * 0.0750759f
                b[2] = 0.96900f * b[2] + rawWhite * 0.1538520f
                b[3] = 0.86650f * b[3] + rawWhite * 0.3104856f
                b[4] = 0.55000f * b[4] + rawWhite * 0.5329522f
                b[5] = -0.7616f * b[5] - rawWhite * 0.0168980f
                val pinkOut = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + rawWhite * pinkRes) * 0.11f * swell * windSigh
                b[6] = rawWhite * 0.115926f

                // 3. Gaia Rumble - Deep and stable Brown Noise
                val brownLP = 0.995f 
                lastOutBrown = lastOutBrown * brownLP + rawWhite * (1f - brownLP)
                val brownOut = lastOutBrown * 2.2f * (sin(phase1 * 0.4f) * 0.15f + 0.85f) * windSigh

                // 4. Rain Forest (Varying drop density and "wetness" with thunder)
                val rainWhite = rawWhite - lastWhite 
                val dropThreshold = 0.997f - sin(tonePhase1) * 0.002f
                val drop = if (Random.nextFloat() > dropThreshold) Random.nextFloat() * 0.6f else 0f
                
                // Thunder logic
                sampleCount++
                if (sampleCount >= nextThunderSample && activeVolumes.getOrDefault(NoiseType.RAIN_FOREST, 0f) > 0.5f) {
                    thunderLevel = 1.0f
                    nextThunderSample = sampleCount + (SAMPLE_RATE * 10 + Random.nextInt(SAMPLE_RATE * 30))
                }
                
                val thunderNoise = if (thunderLevel > 0f) {
                    // Deep low-frequency rumble for thunder
                    lastOutBrown * 4.0f * thunderLevel
                } else 0f
                
                if (thunderLevel > 0f) {
                    thunderLevel *= 0.99995f // Slow decay for rumble
                    if (thunderLevel < 0.01f) thunderLevel = 0f
                }

                val rainOut = (rainWhite * 0.4f + drop) * (sin(phase4) * 0.12f + 0.88f) + (thunderNoise * 0.3f)

                // 5. Ocean Waves with Seagulls
                val waveSwell = (sin(phase1 * 0.3f) * 0.4f + 0.6f) // Very slow wave swell
                val oceanOut = (pinkOut * 0.8f + brownOut * 0.4f) * waveSwell
                
                // Sea Gull logic (procedural "squawk")
                if (sampleCount >= nextSeaGullSample && activeVolumes.getOrDefault(NoiseType.OCEAN_WAVES, 0f) > 0.3f) {
                    seaGullLevel = 0.8f
                    nextSeaGullSample = sampleCount + (SAMPLE_RATE * 15 + Random.nextInt(SAMPLE_RATE * 45))
                }
                
                val seaGullNoise = if (seaGullLevel > 0f) {
                    val gullFreq = (sin(sampleCount * 0.05f) * 0.1f + 1.0f) // Frequency modulation for the squawk
                    (sin(sampleCount * gullFreq * 0.15f) * seaGullLevel * 0.15f * (rawWhite * 0.2f + 0.8f))
                } else 0f
                
                if (seaGullLevel > 0f) {
                    seaGullLevel *= 0.9999f 
                    if (seaGullLevel < 0.001f) seaGullLevel = 0f
                }

                val oceanFinalOut = oceanOut + seaGullNoise

                // Mix active noises
                var mixed = 0f
                mixed += whiteOut * activeVolumes.getOrDefault(NoiseType.DEEP_SPACE, 0f)
                mixed += pinkOut * activeVolumes.getOrDefault(NoiseType.STELLAR_WIND, 0f)
                mixed += brownOut * activeVolumes.getOrDefault(NoiseType.EARTH_RUMBLE, 0f)
                mixed += rainOut * activeVolumes.getOrDefault(NoiseType.RAIN_FOREST, 0f)
                mixed += oceanFinalOut * activeVolumes.getOrDefault(NoiseType.OCEAN_WAVES, 0f)
                
                // Soft clipping / Warmth
                val finalOut = (mixed.coerceIn(-1.5f, 1.5f) / 1.5f).coerceIn(-1f, 1f) * MAX_TOTAL_VOLUME
                samples[i] = (finalOut * Short.MAX_VALUE).toInt().toShort()
            }
            track.write(samples, 0, samples.size)
        }

        track.stop(); track.release()
        lock.withLock { isPlaying = false }
    }

    fun stop() {
        lock.withLock {
            targetTypes = emptySet()
            isPlaying = false
        }
    }
}
