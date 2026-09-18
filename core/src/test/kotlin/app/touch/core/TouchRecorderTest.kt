package app.touch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchRecorderTest {
    @Test
    fun recordsSilenceWithoutPointers() {
        val recorder = TouchRecorder()
        recorder.start()
        recorder.sample()
        recorder.sample()

        assertEquals(listOf(0, 0), recorder.stop().amplitudes)
    }

    @Test
    fun recordsHighestActivePointer() {
        val recorder = TouchRecorder()
        recorder.start()
        recorder.updatePointer(1, 70)
        recorder.updatePointer(2, 210)
        recorder.sample()

        assertEquals(listOf(210), recorder.stop().amplitudes)
    }

    @Test
    fun preservesPeakThatWasReleasedWithinInterval() {
        val recorder = TouchRecorder()
        recorder.start()
        recorder.updatePointer(1, 240)
        recorder.removePointer(1)
        recorder.sample()
        recorder.sample()

        assertEquals(listOf(240, 0), recorder.stop().amplitudes)
    }

    @Test
    fun usesCurrentValueAfterPeakInterval() {
        val recorder = TouchRecorder()
        recorder.start()
        recorder.updatePointer(1, 220)
        recorder.updatePointer(1, 80)
        recorder.sample()
        recorder.sample()

        assertEquals(listOf(220, 80), recorder.stop().amplitudes)
    }

    @Test
    fun stopsAtConfiguredLimit() {
        val recorder = TouchRecorder(samplePeriodMillis = 10, maxDurationMillis = 30)
        recorder.start()

        assertTrue(recorder.sample())
        assertTrue(recorder.sample())
        assertFalse(recorder.sample())
        assertEquals(30, recorder.stop().durationMillis)
    }
}
