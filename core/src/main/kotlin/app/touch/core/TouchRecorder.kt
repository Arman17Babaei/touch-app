package app.touch.core

class TouchRecorder(
    val samplePeriodMillis: Int = Touch.DEFAULT_SAMPLE_PERIOD_MILLIS,
    maxDurationMillis: Int = DEFAULT_MAX_DURATION_MILLIS,
    private val onSample: ((Int) -> Unit)? = null,
) {
    private val maxSamples = maxDurationMillis / samplePeriodMillis
    private val activePointers = mutableMapOf<Long, Int>()
    private val samples = ArrayList<Int>(maxSamples)
    private var intervalPeak = 0

    var isRecording: Boolean = false
        private set

    val elapsedMillis: Int
        get() = samples.size * samplePeriodMillis

    init {
        require(samplePeriodMillis > 0)
        require(maxDurationMillis >= samplePeriodMillis)
    }

    fun start() {
        samples.clear()
        activePointers.clear()
        intervalPeak = 0
        isRecording = true
    }

    fun updatePointer(pointerId: Long, amplitude: Int) {
        if (!isRecording) return
        require(amplitude in 1..Touch.MAX_AMPLITUDE)
        activePointers[pointerId] = amplitude
        intervalPeak = maxOf(intervalPeak, amplitude)
    }

    fun removePointer(pointerId: Long) {
        activePointers.remove(pointerId)
    }

    fun cancelPointers() {
        activePointers.clear()
    }

    /** Captures the highest value seen during this interval or still held at its end. */
    fun sample(): Boolean {
        if (!isRecording) return false
        val heldAmplitude = activePointers.values.maxOrNull() ?: 0
        val value = maxOf(intervalPeak, heldAmplitude)
        samples += value
        onSample?.invoke(value)
        intervalPeak = heldAmplitude
        if (samples.size >= maxSamples) {
            isRecording = false
        }
        return isRecording
    }

    fun stop(): Touch {
        isRecording = false
        activePointers.clear()
        intervalPeak = 0
        return Touch(samplePeriodMillis, samples.toList())
    }

    companion object {
        const val DEFAULT_MAX_DURATION_MILLIS = 30_000
    }
}
