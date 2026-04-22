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
        private const val MAX_TOTAL_VOLUME = 0.5f
        private const val VOL_FADE_SPEED = 0.00002f // Slower fade for smooth atmospheric changes
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var thread: Thread? = null
    
    private var masterVolume = 0.5f
    private var targetMasterVolume = 0.5f
    private val activeVolumes = NoiseType.entries.associateWith { 0f }.toMutableMap()
    private val targetVolumes = NoiseType.entries.associateWith { 0f }.toMutableMap()
    
    private val lock = ReentrantLock()

    enum class NoiseType(val resId: Int) {
        DEEP_SPACE(R.string.mode_white),
        STELLAR_WIND(R.string.mode_pink),
        EARTH_RUMBLE(R.string.mode_brown),
        RAIN_FOREST(R.string.mode_rain),
        OCEAN_WAVES(R.string.mode_ocean)
    }

    fun setMasterVolume(volume: Float) {
        lock.withLock {
            targetMasterVolume = volume.coerceIn(0.1f, 1.0f)
        }
    }

    fun setModes(types: Set<NoiseType>) {
        lock.withLock {
            Log.d(TAG, "Applying manual modes: $types")
            NoiseType.entries.forEach { type ->
                targetVolumes[type] = if (types.contains(type)) 1f else 0f
            }
            if (!isPlaying && types.isNotEmpty()) {
                isPlaying = true
                startThread()
            }
        }
    }

    fun setWeightedModes(weights: Map<NoiseType, Float>) {
        lock.withLock {
            NoiseType.entries.forEach { type ->
                targetVolumes[type] = weights.getOrDefault(type, 0f)
            }
            if (!isPlaying && targetVolumes.values.any { it > 0f }) {
                isPlaying = true
                startThread()
            }
        }
    }

    private fun startThread() {
        thread = Thread { runGenerationLoop() }.apply { start() }
    }

    private fun runGenerationLoop() {
        Log.i(TAG, "Audio generation thread started")
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
        var lastWhite = 0f
        
        // Phase accumulators
        var phase1 = 0f; var phase2 = 0f; var phase3 = 0f; var phase4 = 0f
        var tonePhase1 = 0f; var tonePhase2 = 0f; var windPhase = 0f
        var humPhase = 0f
        var seaGullPhase = 0f; var forestBirdPhase = 0f
        
        // Resonant filters for wind
        var windLP1 = 0f; var windLP2 = 0f

        // Event states
        var thunderLevel = 0f
        var earthquakeLevel = 0f
        var nextThunderSample = (SAMPLE_RATE * 15 + Random.nextInt(SAMPLE_RATE * 30))
        var nextEarthquakeSample = (SAMPLE_RATE * 10 + Random.nextInt(SAMPLE_RATE * 30))
        var seaGullLevel = 0f
        var nextSeaGullSample = (SAMPLE_RATE * 5 + Random.nextInt(SAMPLE_RATE * 15))
        var forestBirdLevel = 0f
        var nextForestBirdSample = (SAMPLE_RATE * 8 + Random.nextInt(SAMPLE_RATE * 20))
        var sampleCount = 0

        while (true) {
            val localIsPlaying = lock.withLock { isPlaying }
            val targets = lock.withLock { targetVolumes.toMap() }

            // Check if we should stop
            val activeAny = activeVolumes.values.any { it > 0.001f }
            if (!localIsPlaying && !activeAny) break

            for (i in samples.indices) {
                sampleCount++
                val rawWhite = Random.nextFloat() * 2 - 1
                
                // 0. Update master and individual volumes per-sample
                val masterTarget = lock.withLock { targetMasterVolume }
                if (masterVolume != masterTarget) {
                    masterVolume = if (masterTarget > masterVolume) (masterVolume + VOL_FADE_SPEED).coerceAtMost(masterTarget)
                                   else (masterVolume - VOL_FADE_SPEED).coerceAtLeast(masterTarget)
                }

                NoiseType.entries.forEach { type ->
                    val target = targets[type] ?: 0f
                    val current = activeVolumes[type] ?: 0f
                    if (current != target) {
                        activeVolumes[type] = if (target > current) (current + VOL_FADE_SPEED).coerceAtMost(target)
                                               else (current - VOL_FADE_SPEED).coerceAtLeast(target)
                    }
                }

                // Global LFOs
                phase1 = (phase1 + 0.000021f) % 6.2831855f
                phase2 = (phase2 + 0.000073f) % 6.2831855f
                phase3 = (phase3 + 0.000137f) % 6.2831855f
                phase4 = (phase4 + 0.000511f) % 6.2831855f
                windPhase = (windPhase + 0.000011f) % 6.2831855f
                tonePhase1 = (tonePhase1 + 0.000005f) % 6.2831855f
                tonePhase2 = (tonePhase2 + 0.000003f) % 6.2831855f
                
                val windSigh = (sin(windPhase.toDouble()).toFloat() * 0.15f + 0.85f)

                // 1. DEEP_SPACE: Engine Hum + Natural White
                val whiteLP = 0.96f + sin(tonePhase1.toDouble()).toFloat() * 0.01f
                lastWhite = lastWhite * whiteLP + rawWhite * (1f - whiteLP)
                humPhase = (humPhase + (55f * 6.2831855f / SAMPLE_RATE)) % 6.2831855f
                // Pure sine hum, no harmonics to avoid CRT "buzz"
                val hum = sin(humPhase.toDouble()).toFloat() * 0.6f + sin((humPhase * 0.5f).toDouble()).toFloat() * 0.2f
                val whiteOut = (lastWhite * 0.25f + hum * 0.6f) * 0.7f

                // 2. STELLAR_WIND: Ethereal Pink + Organic Gusts (No sine whistle)
                b[0] = 0.99886f * b[0] + rawWhite * 0.0555179f
                b[1] = 0.99332f * b[1] + rawWhite * 0.0750759f
                b[2] = 0.96900f * b[2] + rawWhite * 0.1538520f
                b[3] = 0.86650f * b[3] + rawWhite * 0.3104856f
                b[4] = 0.55000f * b[4] + rawWhite * 0.5329522f
                b[5] = -0.7616f * b[5] - rawWhite * 0.0168980f
                val pinkBase = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + rawWhite * 0.5f) * 0.11f
                b[6] = rawWhite * 0.115926f
                
                // Use a modulated resonant filter on pink noise for a soft "shhh-whoosh"
                val filterFreq = 0.85f + sin(phase2.toDouble()).toFloat() * 0.05f
                windLP1 = windLP1 * filterFreq + pinkBase * (1f - filterFreq)
                windLP2 = windLP2 * filterFreq + windLP1 * (1f - filterFreq)
                
                val pinkOut = (windLP2 * 1.5f) * windSigh

                // 3. EARTH_RUMBLE: Dark Static Rumble (No waves)
                val brownLP = 0.9985f
                lastOutBrown = lastOutBrown * brownLP + rawWhite * (1f - brownLP)
                
                if (sampleCount >= nextEarthquakeSample && (activeVolumes[NoiseType.EARTH_RUMBLE] ?: 0f) > 0.4f) {
                    earthquakeLevel = 1.0f
                    nextEarthquakeSample = sampleCount + (SAMPLE_RATE * 20 + Random.nextInt(SAMPLE_RATE * 40))
                }
                var earthquakeNoise = 0f
                if (earthquakeLevel > 0f) {
                    // Seismic tremor: irregular grinding
                    val tremor = (sin((sampleCount * 0.0002f).toDouble()).toFloat() * sin((sampleCount * 0.00007f).toDouble()).toFloat() * 0.5f + 0.5f)
                    earthquakeNoise = lastOutBrown * 12.0f * earthquakeLevel * tremor
                    earthquakeLevel *= 0.99997f
                    if (earthquakeLevel < 0.01f) earthquakeLevel = 0f
                }

                if (sampleCount >= nextThunderSample && ((activeVolumes[NoiseType.EARTH_RUMBLE] ?: 0f) > 0.3f || (activeVolumes[NoiseType.RAIN_FOREST] ?: 0f) > 0.3f)) {
                    thunderLevel = 1.0f
                    nextThunderSample = sampleCount + (SAMPLE_RATE * 25 + Random.nextInt(SAMPLE_RATE * 50))
                }
                val thunderNoise = if (thunderLevel > 0f) lastOutBrown * 8.0f * thunderLevel else 0f
                if (thunderLevel > 0f) { thunderLevel *= 0.99996f; if (thunderLevel < 0.01f) thunderLevel = 0f }

                val brownOut = (lastOutBrown * 8.0f) + earthquakeNoise + (thunderNoise * 0.4f)

                // 4. RAIN_FOREST: Rain + Birds + Thunder
                val rainWhite = rawWhite - lastWhite 
                val dropThreshold = 0.996f - sin(tonePhase1.toDouble()).toFloat() * 0.002f
                val drop = if (Random.nextFloat() > dropThreshold) Random.nextFloat() * 0.5f else 0f
                
                if (sampleCount >= nextForestBirdSample && (activeVolumes[NoiseType.RAIN_FOREST] ?: 0f) > 0.4f) {
                    forestBirdLevel = 1.0f
                    nextForestBirdSample = sampleCount + (SAMPLE_RATE * 5 + Random.nextInt(SAMPLE_RATE * 20))
                }
                var forestBirdNoise = 0f
                if (forestBirdLevel > 0f) {
                    val pulse = sin((sampleCount * 0.015f).toDouble()).toFloat()
                    if (pulse > 0.0f) {
                        val bFreq = 3200f + sin((sampleCount * 0.04f).toDouble()).toFloat() * 1000f
                        forestBirdPhase = (forestBirdPhase + (bFreq * 6.2831855f / SAMPLE_RATE)) % 6.2831855f
                        forestBirdNoise = sin(forestBirdPhase.toDouble()).toFloat() * forestBirdLevel * 0.1f * pulse
                    }
                    forestBirdLevel *= 0.99994f
                    if (forestBirdLevel < 0.001f) forestBirdLevel = 0f
                }
                val rainOut = (rainWhite * 0.35f + drop) * (sin(phase4.toDouble()).toFloat() * 0.1f + 0.9f) + (thunderNoise * 0.4f) + forestBirdNoise

                // 5. OCEAN_WAVES: Swell + Seagulls
                val oceanSwell = (sin((phase1 * 0.25f).toDouble()).toFloat() * 0.5f + 0.5f)
                val oceanBase = (pinkBase * 1.2f) * (0.4f + 0.6f * oceanSwell)
                
                if (sampleCount >= nextSeaGullSample && (activeVolumes[NoiseType.OCEAN_WAVES] ?: 0f) > 0.4f) {
                    seaGullLevel = 1.0f
                    nextSeaGullSample = sampleCount + (SAMPLE_RATE * 10 + Random.nextInt(SAMPLE_RATE * 25))
                }
                var seaGullNoise = 0f
                if (seaGullLevel > 0f) {
                    val progress = 1f - seaGullLevel
                    val jitter = sin((sampleCount * 0.01f).toDouble()).toFloat() * 50f
                    val basePitch = if (progress < 0.15f) 1200f + (progress / 0.15f) * 1400f 
                                    else 2600f - ((progress - 0.15f) / 0.85f) * 1800f
                    val pitchSlide = basePitch + jitter
                    seaGullPhase = (seaGullPhase + (pitchSlide * 6.2831855f / SAMPLE_RATE)) % 6.2831855f
                    val carrier = sin(seaGullPhase.toDouble()).toFloat()
                    val gargle = sin((sampleCount * 0.02f).toDouble()).toFloat() * 0.5f + 0.5f
                    var sig = (carrier + (rawWhite * 0.3f))
                    sig = (sig * 4.0f).coerceIn(-1f, 1f)
                    val env = if (progress < 0.1f) progress / 0.1f 
                              else if (progress < 0.4f) 1f 
                              else (seaGullLevel / 0.6f)
                    seaGullNoise = sig * env * gargle * 0.18f
                    seaGullLevel *= 0.99993f
                    if (seaGullLevel < 0.001f) seaGullLevel = 0f
                }
                val oceanFinalOut = oceanBase + seaGullNoise

                // Mix active noises
                var mixed = 0f
                mixed += whiteOut * (activeVolumes[NoiseType.DEEP_SPACE] ?: 0f)
                mixed += pinkOut * (activeVolumes[NoiseType.STELLAR_WIND] ?: 0f)
                mixed += brownOut * (activeVolumes[NoiseType.EARTH_RUMBLE] ?: 0f)
                mixed += rainOut * (activeVolumes[NoiseType.RAIN_FOREST] ?: 0f)
                mixed += oceanFinalOut * (activeVolumes[NoiseType.OCEAN_WAVES] ?: 0f)
                
                val finalOut = (mixed.coerceIn(-1.5f, 1.5f) / 1.5f).coerceIn(-1f, 1f) * MAX_TOTAL_VOLUME * masterVolume
                samples[i] = (finalOut * Short.MAX_VALUE).toInt().toShort()
            }
            track.write(samples, 0, samples.size)
        }

        track.stop(); track.release()
        lock.withLock { 
            isPlaying = false
            Log.i(TAG, "Audio generation thread finished")
        }
    }

    fun stop() {
        lock.withLock {
            targetVolumes.keys.forEach { targetVolumes[it] = 0f }
            isPlaying = false
        }
    }
}
