package app.touch.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchOptimizerTest {
    @Test
    fun mergesNearbyAmplitudesAndPreservesSilence() {
        val waveform = TouchOptimizer(amplitudeTolerance = 8).optimize(
            Touch(amplitudes = listOf(100, 102, 103, 0, 0, 200, 201)),
        )

        assertArrayEquals(longArrayOf(30, 20, 20), waveform.timingsMillis)
        assertArrayEquals(intArrayOf(121, 0, 208), waveform.amplitudes)
        assertEquals(70, waveform.durationMillis)
    }

    @Test
    fun rangePreventsGradualDriftFromOverMerging() {
        val waveform = TouchOptimizer(amplitudeTolerance = 8).optimize(
            Touch(amplitudes = listOf(100, 108, 116)),
        )

        assertArrayEquals(longArrayOf(20, 10), waveform.timingsMillis)
        assertArrayEquals(intArrayOf(123, 133), waveform.amplitudes)
    }

    @Test
    fun zeroToleranceStillCollapsesIdenticalSamples() {
        val waveform = TouchOptimizer(amplitudeTolerance = 0).optimize(
            Touch(amplitudes = listOf(80, 80, 80)),
        )

        assertArrayEquals(longArrayOf(30), waveform.timingsMillis)
        assertArrayEquals(intArrayOf(101), waveform.amplitudes)
    }

    @Test
    fun convertsStrengthToPulseWidthWhenAmplitudeControlIsUnavailable() {
        val waveform = TouchOptimizer().optimize(
            Touch(amplitudes = List(10) { 64 } + List(10) { 192 } + listOf(0, 0)),
            supportsAmplitudeControl = false,
        )

        assertArrayEquals(longArrayOf(25, 75, 75, 45), waveform.timingsMillis)
        assertArrayEquals(intArrayOf(255, 0, 255, 0), waveform.amplitudes)
        assertEquals(220, waveform.durationMillis)
    }

    @Test
    fun preservesFullStrengthAndSilenceInPulseFallback() {
        val waveform = TouchOptimizer().optimize(
            Touch(amplitudes = List(10) { 255 } + List(5) { 0 }),
            supportsAmplitudeControl = false,
        )

        assertArrayEquals(longArrayOf(100, 50), waveform.timingsMillis)
        assertArrayEquals(intArrayOf(255, 0), waveform.amplitudes)
    }

    @Test
    fun acceptsEmptyTouch() {
        val waveform = TouchOptimizer().optimize(Touch(amplitudes = emptyList()))

        assertEquals(0, waveform.timingsMillis.size)
        assertEquals(0, waveform.amplitudes.size)
    }
}
