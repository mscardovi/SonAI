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
        private const val VOL_FADE_SPEED = 0.00002f 
        
        private const val TWO_PI = 6.2831855f
        private const val HUM_FREQ = 55f
        private const val THUNDER_PROB = 0.99996f
        private const val EARTHQUAKE_PROB = 0.99997f
        private const val RAIN_THRESHOLD_BASE = 0.996f
        private const val SEAGULL_PROB = 0.99993f
        private const val BIRD_PROB = 0.99994f
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var thread: Thread? = null
    
    private var masterVolume = 0.5f
    private var targetMasterVolume = 0.5f
    private val activeVolumes = NoiseType.entries.associateWith { 0f }.toMutableMap()
    private val targetVolumes = NoiseType.entries.associateWith { 0f }.toMutableMap()

    private var binauralType = BinauralType.OFF
    private var binauralVolume = 0f
    private var targetBinauralVolume = 0f
    
    private val lock = ReentrantLock()

    enum class NoiseType(val resId: Int) {
        DEEP_SPACE(R.string.mode_white),
        STELLAR_WIND(R.string.mode_pink),
        EARTH_RUMBLE(R.string.mode_brown),
        RAIN_FOREST(R.string.mode_rain),
        OCEAN_WAVES(R.string.mode_ocean)
    }

    enum class BinauralType(val baseFreq: Float, val beatFreq: Float) {
        OFF(0f, 0f),
        ALPHA(150f, 10f), // Focus, calm
        BETA(150f, 20f),  // High focus, alertness
        THETA(150f, 6f),  // Relaxation, creativity
        DELTA(150f, 2.5f) // Deep sleep, healing
    }

    fun setBinauralBeat(type: BinauralType, volume: Float) {
        lock.withLock {
            binauralType = type
            targetBinauralVolume = volume.coerceIn(0f, 1f)
            if (!isPlaying && targetBinauralVolume > 0f) {
                isPlaying = true
                startThread()
            }
        }
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
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )

        val track = try {
            createAudioTrack(bufferSize)
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "Failed to create AudioTrack", e)
            lock.withLock { isPlaying = false }
            return
        }

        lock.withLock { audioTrack = track }
        track.play()

        val samples = ShortArray(bufferSize / 2) // ShortArray size for Stereo (L+R shorts)
        val state = GeneratorState()

        while (true) {
            val localIsPlaying = lock.withLock { isPlaying }

            if (!localIsPlaying && activeVolumes.values.all { it <= 0.001f } && binauralVolume <= 0.001f) break

            for (i in 0 until samples.size step 2) {
                updateVolumes()
                state.updatePhases()
                val rawWhite = Random.nextFloat() * 2 - 1
                
                val mixed = generateMix(rawWhite, state)
                
                // Binaural logic
                var leftBinaural = 0f
                var rightBinaural = 0f
                if (binauralType != BinauralType.OFF && binauralVolume > 0f) {
                    val freqL = binauralType.baseFreq
                    val freqR = binauralType.baseFreq + binauralType.beatFreq
                    state.binauralPhaseL = (state.binauralPhaseL + (freqL * TWO_PI / SAMPLE_RATE)) % TWO_PI
                    state.binauralPhaseR = (state.binauralPhaseR + (freqR * TWO_PI / SAMPLE_RATE)) % TWO_PI
                    leftBinaural = sin(state.binauralPhaseL.toDouble()).toFloat() * binauralVolume * 0.3f
                    rightBinaural = sin(state.binauralPhaseR.toDouble()).toFloat() * binauralVolume * 0.3f
                }

                val finalOutL = ((mixed + leftBinaural).coerceIn(-1.5f, 1.5f) / 1.5f).coerceIn(-1f, 1f) * MAX_TOTAL_VOLUME * masterVolume
                val finalOutR = ((mixed + rightBinaural).coerceIn(-1.5f, 1.5f) / 1.5f).coerceIn(-1f, 1f) * MAX_TOTAL_VOLUME * masterVolume
                
                samples[i] = (finalOutL * Short.MAX_VALUE).toInt().toShort()
                samples[i + 1] = (finalOutR * Short.MAX_VALUE).toInt().toShort()
            }
            track.write(samples, 0, samples.size)
        }

        track.stop(); track.release()
        lock.withLock { 
            isPlaying = false
            Log.i(TAG, "Audio generation thread finished")
        }
    }

    private fun createAudioTrack(bufferSize: Int): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
    }

    private fun updateVolumes() {
        val masterTarget = lock.withLock { targetMasterVolume }
        if (masterVolume != masterTarget) {
            masterVolume = if (masterTarget > masterVolume) (masterVolume + VOL_FADE_SPEED).coerceAtMost(masterTarget)
            else (masterVolume - VOL_FADE_SPEED).coerceAtLeast(masterTarget)
        }

        NoiseType.entries.forEach { type ->
            val target = targetVolumes[type] ?: 0f
            val current = activeVolumes[type] ?: 0f
            if (current != target) {
                activeVolumes[type] = if (target > current) (current + VOL_FADE_SPEED).coerceAtMost(target)
                else (current - VOL_FADE_SPEED).coerceAtLeast(target)
            }
        }

        if (binauralVolume != targetBinauralVolume) {
            binauralVolume = if (targetBinauralVolume > binauralVolume) (binauralVolume + VOL_FADE_SPEED).coerceAtMost(targetBinauralVolume)
            else (binauralVolume - VOL_FADE_SPEED).coerceAtLeast(targetBinauralVolume)
        }
    }

    private class GeneratorState {
        val pinkFilter = FloatArray(7)
        var lastOutBrown = 0f
        var lastWhite = 0f
        var phase1 = 0f; var phase2 = 0f; var phase3 = 0f; var phase4 = 0f
        var tonePhase1 = 0f; var tonePhase2 = 0f; var windPhase = 0f
        var humPhase = 0f
        var seaGullPhase = 0f; var forestBirdPhase = 0f
        var windLP1 = 0f; var windLP2 = 0f
        var thunderLevel = 0f; var earthquakeLevel = 0f
        var seaGullLevel = 0f; var forestBirdLevel = 0f
        var binauralPhaseL = 0f; var binauralPhaseR = 0f
        var sampleCount = 0
        var nextThunderSample = (SAMPLE_RATE * 15 + Random.nextInt(SAMPLE_RATE * 30))
        var nextEarthquakeSample = (SAMPLE_RATE * 10 + Random.nextInt(SAMPLE_RATE * 30))
        var nextSeaGullSample = (SAMPLE_RATE * 5 + Random.nextInt(SAMPLE_RATE * 15))
        var nextForestBirdSample = (SAMPLE_RATE * 8 + Random.nextInt(SAMPLE_RATE * 20))

        fun updatePhases() {
            sampleCount++
            phase1 = (phase1 + 0.000021f) % TWO_PI
            phase2 = (phase2 + 0.000073f) % TWO_PI
            phase3 = (phase3 + 0.000137f) % TWO_PI
            phase4 = (phase4 + 0.000511f) % TWO_PI
            windPhase = (windPhase + 0.000011f) % TWO_PI
            tonePhase1 = (tonePhase1 + 0.000005f) % TWO_PI
            tonePhase2 = (tonePhase2 + 0.000003f) % TWO_PI
        }
    }

    private fun generateMix(rawWhite: Float, s: GeneratorState): Float {
        var mixed = 0f
        mixed += generateDeepSpace(rawWhite, s) * (activeVolumes[NoiseType.DEEP_SPACE] ?: 0f)
        mixed += generateStellarWind(rawWhite, s) * (activeVolumes[NoiseType.STELLAR_WIND] ?: 0f)
        val thunder = updateThunder(s)
        mixed += generateEarthRumble(rawWhite, s, thunder) * (activeVolumes[NoiseType.EARTH_RUMBLE] ?: 0f)
        mixed += generateRainForest(rawWhite, s, thunder) * (activeVolumes[NoiseType.RAIN_FOREST] ?: 0f)
        mixed += generateOceanWaves(rawWhite, s) * (activeVolumes[NoiseType.OCEAN_WAVES] ?: 0f)
        return mixed
    }

    private fun generateDeepSpace(rawWhite: Float, s: GeneratorState): Float {
        val whiteLP = 0.96f + sin(s.tonePhase1.toDouble()).toFloat() * 0.01f
        s.lastWhite = s.lastWhite * whiteLP + rawWhite * (1f - whiteLP)
        s.humPhase = (s.humPhase + (HUM_FREQ * TWO_PI / SAMPLE_RATE)) % TWO_PI
        val hum = sin(s.humPhase.toDouble()).toFloat() * 0.6f + sin((s.humPhase * 0.5f).toDouble()).toFloat() * 0.2f
        return (s.lastWhite * 0.25f + hum * 0.6f) * 0.7f
    }

    private fun generateStellarWind(rawWhite: Float, s: GeneratorState): Float {
        val b = s.pinkFilter
        b[0] = 0.99886f * b[0] + rawWhite * 0.0555179f
        b[1] = 0.99332f * b[1] + rawWhite * 0.0750759f
        b[2] = 0.96900f * b[2] + rawWhite * 0.1538520f
        b[3] = 0.86650f * b[3] + rawWhite * 0.3104856f
        b[4] = 0.55000f * b[4] + rawWhite * 0.5329522f
        b[5] = -0.7616f * b[5] - rawWhite * 0.0168980f
        val pinkBase = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + rawWhite * 0.5f) * 0.11f
        b[6] = rawWhite * 0.115926f
        
        val filterFreq = 0.85f + sin(s.phase2.toDouble()).toFloat() * 0.05f
        s.windLP1 = s.windLP1 * filterFreq + pinkBase * (1f - filterFreq)
        s.windLP2 = s.windLP2 * filterFreq + s.windLP1 * (1f - filterFreq)
        
        val windSigh = (sin(s.windPhase.toDouble()).toFloat() * 0.15f + 0.85f)
        return (s.windLP2 * 1.5f) * windSigh
    }

    private fun updateThunder(s: GeneratorState): Float {
        if (s.sampleCount >= s.nextThunderSample && 
            ((activeVolumes[NoiseType.EARTH_RUMBLE] ?: 0f) > 0.3f || (activeVolumes[NoiseType.RAIN_FOREST] ?: 0f) > 0.3f)) {
            s.thunderLevel = 1.0f
            s.nextThunderSample = s.sampleCount + (SAMPLE_RATE * 25 + Random.nextInt(SAMPLE_RATE * 50))
        }
        val noise = if (s.thunderLevel > 0f) s.lastOutBrown * 8.0f * s.thunderLevel else 0f
        if (s.thunderLevel > 0f) { 
            s.thunderLevel *= THUNDER_PROB
            if (s.thunderLevel < 0.01f) s.thunderLevel = 0f 
        }
        return noise
    }

    private fun generateEarthRumble(rawWhite: Float, s: GeneratorState, thunder: Float): Float {
        val brownLP = 0.9985f
        s.lastOutBrown = s.lastOutBrown * brownLP + rawWhite * (1f - brownLP)
        
        if (s.sampleCount >= s.nextEarthquakeSample && (activeVolumes[NoiseType.EARTH_RUMBLE] ?: 0f) > 0.4f) {
            s.earthquakeLevel = 1.0f
            s.nextEarthquakeSample = s.sampleCount + (SAMPLE_RATE * 20 + Random.nextInt(SAMPLE_RATE * 40))
        }
        var earthquakeNoise = 0f
        if (s.earthquakeLevel > 0f) {
            val tremor = (sin((s.sampleCount * 0.0002f).toDouble()).toFloat() * sin((s.sampleCount * 0.00007f).toDouble()).toFloat() * 0.5f + 0.5f)
            earthquakeNoise = s.lastOutBrown * 12.0f * s.earthquakeLevel * tremor
            s.earthquakeLevel *= EARTHQUAKE_PROB
            if (s.earthquakeLevel < 0.01f) s.earthquakeLevel = 0f
        }
        return (s.lastOutBrown * 8.0f) + earthquakeNoise + (thunder * 0.4f)
    }

    private fun generateRainForest(rawWhite: Float, s: GeneratorState, thunder: Float): Float {
        val rainWhite = rawWhite - s.lastWhite 
        val dropThreshold = RAIN_THRESHOLD_BASE - sin(s.tonePhase1.toDouble()).toFloat() * 0.002f
        val drop = if (Random.nextFloat() > dropThreshold) Random.nextFloat() * 0.5f else 0f
        
        if (s.sampleCount >= s.nextForestBirdSample && (activeVolumes[NoiseType.RAIN_FOREST] ?: 0f) > 0.4f) {
            s.forestBirdLevel = 1.0f
            s.nextForestBirdSample = s.sampleCount + (SAMPLE_RATE * 5 + Random.nextInt(SAMPLE_RATE * 20))
        }
        var forestBirdNoise = 0f
        if (s.forestBirdLevel > 0f) {
            val pulse = sin((s.sampleCount * 0.015f).toDouble()).toFloat()
            if (pulse > 0.0f) {
                val bFreq = 3200f + sin((s.sampleCount * 0.04f).toDouble()).toFloat() * 1000f
                s.forestBirdPhase = (s.forestBirdPhase + (bFreq * TWO_PI / SAMPLE_RATE)) % TWO_PI
                forestBirdNoise = sin(s.forestBirdPhase.toDouble()).toFloat() * s.forestBirdLevel * 0.1f * pulse
            }
            s.forestBirdLevel *= BIRD_PROB
            if (s.forestBirdLevel < 0.001f) s.forestBirdLevel = 0f
        }
        return (rainWhite * 0.35f + drop) * (sin(s.phase4.toDouble()).toFloat() * 0.1f + 0.9f) + (thunder * 0.4f) + forestBirdNoise
    }

    private fun generateOceanWaves(rawWhite: Float, s: GeneratorState): Float {
        val b = s.pinkFilter
        val pinkBase = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + rawWhite * 0.5f) * 0.11f
        val oceanSwell = (sin((s.phase1 * 0.25f).toDouble()).toFloat() * 0.5f + 0.5f)
        val oceanBase = (pinkBase * 1.2f) * (0.4f + 0.6f * oceanSwell)
        
        if (s.sampleCount >= s.nextSeaGullSample && (activeVolumes[NoiseType.OCEAN_WAVES] ?: 0f) > 0.4f) {
            s.seaGullLevel = 1.0f
            s.nextSeaGullSample = s.sampleCount + (SAMPLE_RATE * 10 + Random.nextInt(SAMPLE_RATE * 25))
        }
        var seaGullNoise = 0f
        if (s.seaGullLevel > 0f) {
            seaGullNoise = updateSeaGull(rawWhite, s)
        }
        return oceanBase + seaGullNoise
    }

    private fun updateSeaGull(rawWhite: Float, s: GeneratorState): Float {
        val progress = 1f - s.seaGullLevel
        val jitter = sin((s.sampleCount * 0.01f).toDouble()).toFloat() * 50f
        val basePitch = if (progress < 0.15f) 1200f + (progress / 0.15f) * 1400f 
                        else 2600f - ((progress - 0.15f) / 0.85f) * 1800f
        val pitchSlide = basePitch + jitter
        s.seaGullPhase = (s.seaGullPhase + (pitchSlide * TWO_PI / SAMPLE_RATE)) % TWO_PI
        val carrier = sin(s.seaGullPhase.toDouble()).toFloat()
        val gargle = sin((s.sampleCount * 0.02f).toDouble()).toFloat() * 0.5f + 0.5f
        var sig = (carrier + (rawWhite * 0.3f))
        sig = (sig * 4.0f).coerceIn(-1f, 1f)
        val env = if (progress < 0.1f) progress / 0.1f 
                  else if (progress < 0.4f) 1f 
                  else (s.seaGullLevel / 0.6f)
        val noise = sig * env * gargle * 0.18f
        s.seaGullLevel *= SEAGULL_PROB
        if (s.seaGullLevel < 0.001f) s.seaGullLevel = 0f
        return noise
    }

    fun stop() {
        lock.withLock {
            targetVolumes.keys.forEach { targetVolumes[it] = 0f }
            isPlaying = false
        }
    }
}
