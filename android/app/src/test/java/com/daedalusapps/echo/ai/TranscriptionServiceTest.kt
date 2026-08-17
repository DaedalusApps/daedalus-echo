package com.daedalusapps.echo.ai

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranscriptionServiceTest {

    private lateinit var context: Context
    private lateinit var service: TranscriptionService

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)
        service = TranscriptionService(context)
    }

    @Test
    fun resample_identity_returnsSameArray() {
        val input = shortArrayOf(100, 200, -300, 0, 32767, -32768)
        val result = service.resample(input, 16000, 16000)
        assertArrayEquals(input, result)
    }

    @Test
    fun resample_48kTo16k_resamplesCorrectLengthAndInterpolates() {
        // 48 kHz to 16 kHz is a 3:1 downsampling ratio
        val sampleCount48k = 48000
        val input = ShortArray(sampleCount48k) { 1000 }
        val result = service.resample(input, 48000, 16000)

        assertEquals(16000, result.size)
        // For a constant signal, output should maintain the same value
        assertTrue(result.all { it == 1000.toShort() })
    }

    @Test
    fun resample_44_1kTo16k_resamplesCorrectLength() {
        // 44.1 kHz to 16 kHz (ratio = 44100 / 16000 = 2.75625)
        val sampleCount44k = 44100
        val input = ShortArray(sampleCount44k) { (it % 1000).toShort() }
        val result = service.resample(input, 44100, 16000)

        // 44100 / (44100 / 16000) = 16000
        assertEquals(16000, result.size)
    }

    @Test
    fun resample_rampInterpolation_producesLinearValues() {
        // 48k to 16k with linear ramp: 0, 30, 60, 90 ...
        val input = ShortArray(48) { (it * 30).toShort() }
        val result = service.resample(input, 48000, 16000)

        assertEquals(16, result.size)
        // Ratio is 3.0, so result[i] corresponds to input[i * 3]
        for (i in 0 until 16) {
            assertEquals((i * 90).toShort(), result[i])
        }
    }

    @Test
    fun resample_boundaryConditions_handlesEmptyAndSingleElement() {
        val empty = ShortArray(0)
        val emptyResult = service.resample(empty, 48000, 16000)
        assertEquals(0, emptyResult.size)

        val single = shortArrayOf(1234)
        val singleResult = service.resample(single, 48000, 16000)
        // (1 / 3.0).toInt() == 0
        assertEquals(0, singleResult.size)

        val twoElements = shortArrayOf(1000, 2000)
        val twoResult = service.resample(twoElements, 32000, 16000)
        // 2 / 2.0 = 1
        assertEquals(1, twoResult.size)
        assertEquals(1000.toShort(), twoResult[0])
    }

    @Test
    fun floatNormalization_shortRange_mapsWithinUnitRange() {
        // PCM 16-bit to Float normalization divides by 32768f
        val minShort = Short.MIN_VALUE // -32768
        val maxShort = Short.MAX_VALUE // 32767
        val zeroShort: Short = 0

        val minFloat = minShort / 32768f
        val maxFloat = maxShort / 32768f
        val zeroFloat = zeroShort / 32768f

        assertEquals(-1.0f, minFloat, 0.00001f)
        assertEquals(0.0f, zeroFloat, 0.00001f)
        assertTrue("Max short should be normalized < 1.0f", maxFloat < 1.0f)
        assertTrue("Max short should be close to 1.0f", maxFloat > 0.9999f)

        // Ensure every value across full 16-bit integer range stays within [-1.0f, 1.0f]
        val testValues = shortArrayOf(Short.MIN_VALUE, -16384, -1, 0, 1, 16384, Short.MAX_VALUE)
        for (v in testValues) {
            val normalized = v / 32768f
            assertTrue("Normalized float $normalized out of bounds [-1.0, 1.0]", normalized in -1.0f..1.0f)
        }
    }
}
