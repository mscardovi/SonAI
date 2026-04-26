package com.sonai.sonai.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.sonai.sonai.R
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sin
import kotlin.random.Random

/**
 * NoiseGenerator provides high-quality procedural audio synthesis.
 * Uses 32-bit float PCM at 48kHz for superior dynamic range and clarity.
 */
class NoiseGenerator {

    private companion object {
        private const val TAG = "NoiseGenerator"
        private const val SAMPLE_RATE = 48000
        private const val MAX_TOTAL_VOLUME = 0.65f

        private const val FADE_DURATION_SECONDS = 1.8f
        private const val VOL_FADE_SPEED = 1.0f / (SAMPLE_RATE * FADE_DURATION_SECONDS)

        private const val TWO_PI = 6.283185307179586
        private const val HUM_FREQ = 55.0

        // Probabilities scaled for 48kHz to maintain consistent timing across sample rates
        private fun scaleProb(p: Double): Double = 1.0 - (1.0 - p) * 44100.0 / SAMPLE_RATE

        private val THUNDER_PROB = scaleProb(0.99996).toFloat()
        private val EARTHQUAKE_PROB = scaleProb(0.99997).toFloat()
        private val SEAGULL_PROB = scaleProb(0.99993).toFloat()
        private val BIRD_PROB = scaleProb(0.99994).toFloat()
        private val RAIN_THRESHOLD_BASE = (1.0 - (1.0 - 0.996) * 44100.0 / SAMPLE_RATE).toFloat()
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

    enum class BinauralType(val baseFreq: Double, val beatFreq: Double) {
        OFF(0.0, 0.0),
        ALPHA(150.0, 10.0), // Focus, calm
        BETA(150.0, 20.0),  // High focus, alertness
        THETA(150.0, 6.0),  // Relaxation, creativity
        DELTA(150.0, 2.5) // Deep sleep, healing
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
        if (thread?.isAlive == true) return
        thread = Thread { runGenerationLoop() }.apply {
            name = "SonAIAudioGen"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun runGenerationLoop() {
        Log.i(TAG, "Audio generation thread started (48kHz Float)")
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )

        val track = try {
            createAudioTrack(bufferSize)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
            lock.withLock { isPlaying = false }
            return
        }

        lock.withLock { audioTrack = track }
        track.play()

        val samples = FloatArray(bufferSize / 2)
        val state = GeneratorState()
        val random = Random(System.nanoTime())

        while (true) {
            // Thread-safe capture of control parameters
            val currentIsPlaying: Boolean
            val mTarget: Float
            val bTarget: Float
            val bType: BinauralType
            val tVols: Map<NoiseType, Float>

            lock.withLock {
                currentIsPlaying = isPlaying
                mTarget = targetMasterVolume
                bTarget = targetBinauralVolume
                bType = binauralType
                tVols = targetVolumes.toMap()
            }

            if (!currentIsPlaying && activeVolumes.values.all { it <= 0.001f } && binauralVolume <= 0.001f) break

            for (i in 0 until samples.size step 2) {
                // Smooth volume transitions
                if (masterVolume != mTarget) {
                    masterVolume =
                        if (mTarget > masterVolume) (masterVolume + VOL_FADE_SPEED).coerceAtMost(
                            mTarget
                        )
                        else (masterVolume - VOL_FADE_SPEED).coerceAtLeast(mTarget)
                }
                if (binauralVolume != bTarget) {
                    binauralVolume =
                        if (bTarget > binauralVolume) (binauralVolume + VOL_FADE_SPEED).coerceAtMost(
                            bTarget
                        )
                        else (binauralVolume - VOL_FADE_SPEED).coerceAtLeast(bTarget)
                }
                NoiseType.entries.forEach { type ->
                    val target = tVols[type] ?: 0f
                    val current = activeVolumes[type] ?: 0f
                    if (current != target) {
                        activeVolumes[type] =
                            if (target > current) (current + VOL_FADE_SPEED).coerceAtMost(target)
                            else (current - VOL_FADE_SPEED).coerceAtLeast(target)
                    }
                }

                state.updatePhases()
                val rawWhite = random.nextFloat() * 2f - 1f

                val mixed = generateMix(rawWhite, state, random)

                // Binaural logic
                var leftBinaural = 0f
                var rightBinaural = 0f
                if (bType != BinauralType.OFF && binauralVolume > 0f) {
                    val freqL = bType.baseFreq
                    val freqR = bType.baseFreq + bType.beatFreq
                    state.binauralPhaseL =
                        (state.binauralPhaseL + (freqL * TWO_PI / SAMPLE_RATE)) % TWO_PI
                    state.binauralPhaseR =
                        (state.binauralPhaseR + (freqR * TWO_PI / SAMPLE_RATE)) % TWO_PI
                    leftBinaural = sin(state.binauralPhaseL).toFloat() * binauralVolume * 0.25f
                    rightBinaural = sin(state.binauralPhaseR).toFloat() * binauralVolume * 0.25f
                }

                // Final mix with soft clipping headroom
                val finalOutL = (mixed + leftBinaural).coerceIn(
                    -1.2f,
                    1.2f
                ) / 1.2f * MAX_TOTAL_VOLUME * masterVolume
                val finalOutR = (mixed + rightBinaural).coerceIn(
                    -1.2f,
                    1.2f
                ) / 1.2f * MAX_TOTAL_VOLUME * masterVolume

                samples[i] = finalOutL
                samples[i + 1] = finalOutR
            }
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        }

        track.stop()
        track.release()
        lock.withLock {
            isPlaying = false
            Log.i(TAG, "Audio generation thread finished")
        }
    }

    private fun createAudioTrack(bufferSize: Int): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
    }

    private class GeneratorState {
        val pinkFilter = FloatArray(7)
        var lastOutBrown = 0f
        var lastOutOceanBrown = 0f
        var lastWhite = 0f
        var phase1 = 0.0
        var phase2 = 0.0
        var phase3 = 0.0
        var phase4 = 0.0
        var tonePhase1 = 0.0
        var tonePhase2 = 0.0
        var windPhase = 0.0
        var humPhase = 0.0
        var seaGullPhase = 0.0
        var forestBirdPhase = 0.0
        var windLP1 = 0f
        var windLP2 = 0f
        var oceanLP1 = 0f
        var oceanLP2 = 0f
        var spaceDrift = 0.5f
        var windDrift = 0.5f
        var earthDrift = 0.5f
        var rainDrift = 0.5f
        var oceanDrift = 0.5f
        var thunderLevel = 0f
        var earthquakeLevel = 0f
        var seaGullLevel = 0f
        var forestBirdLevel = 0f
        var binauralPhaseL = 0.0
        var binauralPhaseR = 0.0
        var sampleCount = 0L
        var nextThunderSample = (SAMPLE_RATE * 15 + Random.nextInt(SAMPLE_RATE * 30)).toLong()
        var nextEarthquakeSample = (SAMPLE_RATE * 10 + Random.nextInt(SAMPLE_RATE * 30)).toLong()
        var nextSeaGullSample = (SAMPLE_RATE * 5 + Random.nextInt(SAMPLE_RATE * 15)).toLong()
        var nextForestBirdSample = (SAMPLE_RATE * 8 + Random.nextInt(SAMPLE_RATE * 20)).toLong()

        fun updatePhases() {
            sampleCount++
            val ratio = 44100.0 / SAMPLE_RATE
            phase1 = (phase1 + 0.000021 * ratio) % TWO_PI
            phase2 = (phase2 + 0.000073 * ratio) % TWO_PI
            phase3 = (phase3 + 0.000137 * ratio) % TWO_PI
            phase4 = (phase4 + 0.000511 * ratio) % TWO_PI
            windPhase = (windPhase + 0.000011 * ratio) % TWO_PI
            tonePhase1 = (tonePhase1 + 0.000005 * ratio) % TWO_PI
            tonePhase2 = (tonePhase2 + 0.000003 * ratio) % TWO_PI
        }
    }

    private fun generateMix(rawWhite: Float, s: GeneratorState, random: Random): Float {
        var mixed = 0f
        mixed += generateDeepSpace(rawWhite, s, random) * (activeVolumes[NoiseType.DEEP_SPACE]
            ?: 0f)
        mixed += generateStellarWind(rawWhite, s, random) * (activeVolumes[NoiseType.STELLAR_WIND]
            ?: 0f)
        val thunder = updateThunder(s, random)
        mixed += generateEarthRumble(
            rawWhite,
            s,
            thunder,
            random
        ) * (activeVolumes[NoiseType.EARTH_RUMBLE] ?: 0f)
        mixed += generateRainForest(
            rawWhite,
            s,
            thunder,
            random
        ) * (activeVolumes[NoiseType.RAIN_FOREST] ?: 0f)
        mixed += generateOceanWaves(rawWhite, s, random) * (activeVolumes[NoiseType.OCEAN_WAVES]
            ?: 0f)
        return mixed
    }

    private fun generateDeepSpace(rawWhite: Float, s: GeneratorState, random: Random): Float {
        if (s.sampleCount % 4096 == 0L) {
            s.spaceDrift = s.spaceDrift * 0.95f + random.nextFloat() * 0.05f
        }
        val driftMod = (sin(s.tonePhase2 * 0.5).toFloat() * 0.1f + 0.9f) * s.spaceDrift

        val whiteLP = 0.94f + sin(s.tonePhase1).toFloat() * 0.02f
        s.lastWhite = s.lastWhite * whiteLP + rawWhite * (1f - whiteLP)

        s.humPhase = (s.humPhase + (HUM_FREQ * TWO_PI / SAMPLE_RATE)) % TWO_PI
        val humSwell = (sin(s.phase1 * 0.1).toFloat() * 0.15f + 0.85f)
        val hum =
            (sin(s.humPhase).toFloat() * 0.6f + sin(s.humPhase * 0.5).toFloat() * 0.2f) * humSwell

        return (s.lastWhite * 0.25f + hum * 0.65f) * 0.7f * (driftMod + 0.5f)
    }

    private fun generateStellarWind(rawWhite: Float, s: GeneratorState, random: Random): Float {
        if (s.sampleCount % 3072 == 0L) {
            s.windDrift = s.windDrift * 0.88f + random.nextFloat() * 0.12f
        }

        val b = s.pinkFilter
        b[0] = 0.99886f * b[0] + rawWhite * 0.0555179f
        b[1] = 0.99332f * b[1] + rawWhite * 0.0750759f
        b[2] = 0.96900f * b[2] + rawWhite * 0.1538520f
        b[3] = 0.86650f * b[3] + rawWhite * 0.3104856f
        b[4] = 0.55000f * b[4] + rawWhite * 0.5329522f
        b[5] = -0.7616f * b[5] - rawWhite * 0.0168980f
        val pinkBase = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + rawWhite * 0.5f) * 0.11f
        b[6] = rawWhite * 0.115926f

        val filterFreq = 0.82f + sin(s.phase2).toFloat() * 0.08f + (s.windDrift * 0.05f)
        s.windLP1 = s.windLP1 * filterFreq + pinkBase * (1f - filterFreq)
        s.windLP2 = s.windLP2 * filterFreq + s.windLP1 * (1f - filterFreq)

        val gustLFO = (sin(s.windPhase).toFloat() * 0.25f + 0.75f)
        val turbulence = (random.nextFloat() * 0.06f + 0.97f)
        return (s.windLP2 * 1.6f) * gustLFO * (s.windDrift + 0.4f) * turbulence
    }

    private fun updateThunder(s: GeneratorState, random: Random): Float {
        if (s.sampleCount >= s.nextThunderSample &&
            ((activeVolumes[NoiseType.EARTH_RUMBLE]
                ?: 0f) > 0.3f || (activeVolumes[NoiseType.RAIN_FOREST] ?: 0f) > 0.3f)
        ) {
            s.thunderLevel = 1.0f
            s.nextThunderSample =
                s.sampleCount + (SAMPLE_RATE * 25 + random.nextInt(SAMPLE_RATE * 50))
        }
        val noise = if (s.thunderLevel > 0f) s.lastOutBrown * 8.0f * s.thunderLevel else 0f
        if (s.thunderLevel > 0f) {
            s.thunderLevel *= THUNDER_PROB
            if (s.thunderLevel < 0.01f) s.thunderLevel = 0f
        }
        return noise
    }

    private fun generateEarthRumble(
        rawWhite: Float,
        s: GeneratorState,
        thunder: Float,
        random: Random
    ): Float {
        if (s.sampleCount % 5120 == 0L) {
            s.earthDrift = s.earthDrift * 0.92f + random.nextFloat() * 0.08f
        }

        val brownLP = 0.9982f + (s.earthDrift * 0.0005f)
        s.lastOutBrown = s.lastOutBrown * brownLP + rawWhite * (1f - brownLP)

        if (s.sampleCount >= s.nextEarthquakeSample && (activeVolumes[NoiseType.EARTH_RUMBLE]
                ?: 0f) > 0.4f
        ) {
            s.earthquakeLevel = 1.0f
            s.nextEarthquakeSample =
                s.sampleCount + (SAMPLE_RATE * 20 + random.nextInt(SAMPLE_RATE * 40))
        }
        var earthquakeNoise = 0f
        if (s.earthquakeLevel > 0f) {
            val tremorMod = (sin(s.phase3 * 0.5).toFloat() * 0.2f + 0.8f)
            val tremor =
                (sin(s.sampleCount * 0.0002).toFloat() * sin(s.sampleCount * 0.00007).toFloat() * 0.5f + 0.5f)
            earthquakeNoise = s.lastOutBrown * 13.0f * s.earthquakeLevel * tremor * tremorMod
            s.earthquakeLevel *= EARTHQUAKE_PROB
            if (s.earthquakeLevel < 0.01f) s.earthquakeLevel = 0f
        }
        val intensity = (sin(s.phase1 * 0.15).toFloat() * 0.1f + 0.9f) * (s.earthDrift + 0.5f)
        return ((s.lastOutBrown * 8.5f) + earthquakeNoise + (thunder * 0.45f)) * intensity
    }

    private fun generateRainForest(
        rawWhite: Float,
        s: GeneratorState,
        thunder: Float,
        random: Random
    ): Float {
        if (s.sampleCount % 2560 == 0L) {
            s.rainDrift = s.rainDrift * 0.85f + random.nextFloat() * 0.15f
        }

        val rainWhite = rawWhite - s.lastWhite
        val rainDensity = RAIN_THRESHOLD_BASE - (s.rainDrift * 0.005f)
        val dropThreshold = rainDensity - sin(s.tonePhase1).toFloat() * 0.002f
        val dropSize = (random.nextFloat() * 0.5f) * (s.rainDrift + 0.5f)
        val drop = if (random.nextFloat() > dropThreshold) dropSize else 0f

        if (s.sampleCount >= s.nextForestBirdSample && (activeVolumes[NoiseType.RAIN_FOREST]
                ?: 0f) > 0.4f
        ) {
            s.forestBirdLevel = 1.0f
            s.nextForestBirdSample =
                s.sampleCount + (SAMPLE_RATE * 5 + random.nextInt(SAMPLE_RATE * 20))
        }
        var forestBirdNoise = 0f
        if (s.forestBirdLevel > 0f) {
            val pulse = sin(s.sampleCount * 0.015).toFloat()
            if (pulse > 0.0f) {
                val bFreq = 3200.0 + sin(s.sampleCount * 0.04) * 1000.0
                s.forestBirdPhase = (s.forestBirdPhase + (bFreq * TWO_PI / SAMPLE_RATE)) % TWO_PI
                forestBirdNoise =
                    sin(s.forestBirdPhase).toFloat() * s.forestBirdLevel * 0.1f * pulse
            }
            s.forestBirdLevel *= BIRD_PROB
            if (s.forestBirdLevel < 0.001f) s.forestBirdLevel = 0f
        }
        val foliageFiltering = (sin(s.phase4 * 0.5).toFloat() * 0.05f + 0.95f)
        return ((rainWhite * 0.35f + drop) * (sin(s.phase4).toFloat() * 0.1f + 0.9f) * foliageFiltering) + (thunder * 0.45f) + forestBirdNoise
    }

    private fun generateOceanWaves(rawWhite: Float, s: GeneratorState, random: Random): Float {
        // Separate Brown noise for the deep 'surge' of the ocean
        val brownLP = 0.999f
        s.lastOutOceanBrown = s.lastOutOceanBrown * brownLP + rawWhite * (1f - brownLP)
        val deepSurge = s.lastOutOceanBrown * 12.0f

        // Pink noise base for the water surface texture
        val b = s.pinkFilter
        val pinkBase = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + rawWhite * 0.5f) * 0.11f

        // Triple-LFO interaction with prime-relative frequencies for deep randomization
        val swell1 = (sin(s.phase1 * 0.18).toFloat() * 0.5f + 0.5f)
        val swell2 = (sin(s.phase1 * 0.25 + 1.2).toFloat() * 0.5f + 0.5f)
        val swell3 = (sin(s.phase1 * 0.07 + 2.8).toFloat() * 0.5f + 0.5f)

        // Ultra-slow random drift updated periodically to avoid mechanical repetition
        if (s.sampleCount % 2048 == 0L) {
            s.oceanDrift = s.oceanDrift * 0.85f + random.nextFloat() * 0.15f
        }

        // Add subtle stochastic turbulence to the combined swell
        val turbulence = (random.nextFloat() * 0.04f - 0.02f)
        val combinedSwell =
            (swell1 * 0.35f + swell2 * 0.25f + swell3 * 0.2f + s.oceanDrift * 0.2f + turbulence).coerceIn(
                0f,
                1f
            )

        // Dynamic "Liquid" filter: steep low-pass that opens up with the swell
        val filterFreq = 0.05f + (combinedSwell * combinedSwell * 0.55f)
        s.oceanLP1 = s.oceanLP1 * (1f - filterFreq) + pinkBase * filterFreq
        s.oceanLP2 = s.oceanLP2 * (1f - filterFreq) + s.oceanLP1 * filterFreq

        // Foam component with non-linear growth for realistic sloshing
        val foam = s.oceanLP2 * 2.2f * (0.1f + combinedSwell * 1.5f)

        // Combine deep rumble and filtered surface sloshing
        val oceanBase = (deepSurge * (0.4f + combinedSwell * 0.6f)) + foam

        if (s.sampleCount >= s.nextSeaGullSample && (activeVolumes[NoiseType.OCEAN_WAVES]
                ?: 0f) > 0.4f
        ) {
            s.seaGullLevel = 1.0f
            s.nextSeaGullSample =
                s.sampleCount + (SAMPLE_RATE * 12 + random.nextInt(SAMPLE_RATE * 20))
        }
        var seaGullNoise = 0f
        if (s.seaGullLevel > 0f) {
            seaGullNoise = updateSeaGull(rawWhite, s)
        }

        return oceanBase * 0.4f + seaGullNoise
    }

    private fun updateSeaGull(rawWhite: Float, s: GeneratorState): Float {
        val progress = 1f - s.seaGullLevel
        val jitter = sin(s.sampleCount * 0.01).toFloat() * 50f
        val basePitch = if (progress < 0.15f) 1200f + (progress / 0.15f) * 1400f
        else 2600f - ((progress - 0.15f) / 0.85f) * 1800f
        val pitchSlide = basePitch + jitter
        s.seaGullPhase = (s.seaGullPhase + (pitchSlide.toDouble() * TWO_PI / SAMPLE_RATE)) % TWO_PI
        val carrier = sin(s.seaGullPhase).toFloat()
        val gargle = sin(s.sampleCount * 0.02).toFloat() * 0.5f + 0.5f
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
