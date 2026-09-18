package app.touch.core

import kotlin.math.roundToInt

data class VibrationWaveform(
    val timingsMillis: LongArray,
    val amplitudes: IntArray,
) {
    init {
        require(timingsMillis.size == amplitudes.size)
        require(timingsMillis.all { it > 0 })
        require(amplitudes.all { it in 0..255 })
    }

    val durationMillis: Long
        get() = timingsMillis.sum()
}

class TouchOptimizer(private val amplitudeTolerance: Int = DEFAULT_TOLERANCE) {
    init {
        require(amplitudeTolerance in 0..254)
    }

    fun optimize(touch: Touch, supportsAmplitudeControl: Boolean = true): VibrationWaveform {
        if (touch.amplitudes.isEmpty()) {
            return VibrationWaveform(longArrayOf(), intArrayOf())
        }

        if (!supportsAmplitudeControl) return optimizeAsPulses(touch)

        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()
        var runDuration = touch.samplePeriodMillis.toLong()
        var runMin = playableAmplitude(touch.amplitudes.first())
        var runMax = runMin
        var runSum = runMin.toLong()
        var runSamples = 1

        touch.amplitudes.drop(1).forEach { rawAmplitude ->
            val amplitude = playableAmplitude(rawAmplitude)
            val sameSilenceState = (amplitude == 0) == (runMin == 0)
            val nextMin = minOf(runMin, amplitude)
            val nextMax = maxOf(runMax, amplitude)
            if (sameSilenceState && nextMax - nextMin <= amplitudeTolerance) {
                runDuration += touch.samplePeriodMillis
                runMin = nextMin
                runMax = nextMax
                runSum += amplitude
                runSamples++
            } else {
                timings += runDuration
                amplitudes += average(runSum, runSamples)
                runDuration = touch.samplePeriodMillis.toLong()
                runMin = amplitude
                runMax = amplitude
                runSum = amplitude.toLong()
                runSamples = 1
            }
        }

        timings += runDuration
        amplitudes += average(runSum, runSamples)
        return VibrationWaveform(timings.toLongArray(), amplitudes.toIntArray())
    }

    /**
     * Preserve zero as silence and map non-zero input linearly over the actuator's useful
     * range. This keeps equal finger movements evenly separated during playback.
     */
    private fun playableAmplitude(amplitude: Int): Int {
        if (amplitude == 0) return 0
        val normalized = (amplitude - 1).toDouble() / 254.0
        return (MIN_PLAYABLE_AMPLITUDE + normalized * (255 - MIN_PLAYABLE_AMPLITUDE))
            .roundToInt()
            .coerceIn(MIN_PLAYABLE_AMPLITUDE, 255)
    }

    /**
     * Motors without amplitude control can only be on or off. Encode strength as the amount
     * of on-time in short frames so a gradient remains perceptibly different instead of being
     * flattened to one continuous full-strength vibration.
     */
    private fun optimizeAsPulses(touch: Touch): VibrationWaveform {
        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()
        val samplesPerFrame = maxOf(1, FALLBACK_FRAME_MILLIS / touch.samplePeriodMillis)
        var index = 0

        fun append(durationMillis: Long, amplitude: Int) {
            if (durationMillis <= 0) return
            if (amplitudes.lastOrNull() == amplitude) {
                timings[timings.lastIndex] += durationMillis
            } else {
                timings += durationMillis
                amplitudes += amplitude
            }
        }

        while (index < touch.amplitudes.size) {
            if (touch.amplitudes[index] == 0) {
                val start = index
                while (index < touch.amplitudes.size && touch.amplitudes[index] == 0) index++
                append((index - start) * touch.samplePeriodMillis.toLong(), 0)
                continue
            }

            val runEnd = touch.amplitudes.subList(index, touch.amplitudes.size).indexOfFirst { it == 0 }
                .let { if (it == -1) touch.amplitudes.size else index + it }
            while (index < runEnd) {
                val end = minOf(index + samplesPerFrame, runEnd)
                val frame = touch.amplitudes.subList(index, end)
                val duration = frame.size * touch.samplePeriodMillis.toLong()
                val averageAmplitude = frame.sum().toDouble() / frame.size
                val requestedOnTime = (duration * averageAmplitude / 255.0).roundToInt().toLong()
                val onTime = requestedOnTime.coerceIn(minOf(MIN_FALLBACK_PULSE_MILLIS, duration), duration)
                append(onTime, 255)
                append(duration - onTime, 0)
                index = end
            }
        }

        return VibrationWaveform(timings.toLongArray(), amplitudes.toIntArray())
    }

    private fun average(sum: Long, count: Int): Int = ((sum + count / 2) / count).toInt()

    companion object {
        const val DEFAULT_TOLERANCE = 8
        const val MIN_PLAYABLE_AMPLITUDE = 32
        const val FALLBACK_FRAME_MILLIS = 100
        const val MIN_FALLBACK_PULSE_MILLIS = 20L
    }
}
