package app.touch.core

data class Touch(
    val samplePeriodMillis: Int = DEFAULT_SAMPLE_PERIOD_MILLIS,
    val amplitudes: List<Int>,
) {
    init {
        require(samplePeriodMillis > 0) { "Sample period must be positive" }
        require(amplitudes.all { it in MIN_AMPLITUDE..MAX_AMPLITUDE }) {
            "Amplitudes must be between $MIN_AMPLITUDE and $MAX_AMPLITUDE"
        }
    }

    val durationMillis: Long
        get() = amplitudes.size.toLong() * samplePeriodMillis

    val isSilent: Boolean
        get() = amplitudes.all { it == 0 }

    companion object {
        const val DEFAULT_SAMPLE_PERIOD_MILLIS = 10
        const val MIN_AMPLITUDE = 0
        const val MAX_AMPLITUDE = 255
    }
}
