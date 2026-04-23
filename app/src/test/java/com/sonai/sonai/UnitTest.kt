package com.sonai.sonai

import com.sonai.sonai.audio.NoiseGenerator
import com.sonai.sonai.service.SoundAnalysisService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Unit tests for sound mapping logic.
 */
class UnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @ParameterizedTest
    @CsvSource(
        "Speech, STELLAR_WIND",
        "Music, STELLAR_WIND",
        "Bird, STELLAR_WIND",
        "Whistle, STELLAR_WIND",
        "Engine, EARTH_RUMBLE",
        "Hammer, EARTH_RUMBLE",
        "Thunder, EARTH_RUMBLE",
        "Rain, RAIN_FOREST",
        "Forest, RAIN_FOREST",
        "Spray, RAIN_FOREST",
        "Ocean, OCEAN_WAVES",
        "Sea, OCEAN_WAVES",
        "Beach, OCEAN_WAVES",
        "Traffic, DEEP_SPACE",
        "Wind, DEEP_SPACE",
        "Keyboard, DEEP_SPACE",
        "Steam, DEEP_SPACE"
    )
    fun `test sound label mapping`(label: String, expectedType: NoiseGenerator.NoiseType) {
        val result = SoundAnalysisService.getNoiseTypeForLabel(label)
        assertEquals(expectedType, result)
    }

    @Test
    fun `test unknown label mapping`() {
        val result = SoundAnalysisService.getNoiseTypeForLabel("Unknown sound")
        assertNull(result)
    }

    @Test
    fun `test case insensitivity in mapping`() {
        val result = SoundAnalysisService.getNoiseTypeForLabel("SPEECH")
        assertEquals(NoiseGenerator.NoiseType.STELLAR_WIND, result)
    }
}
