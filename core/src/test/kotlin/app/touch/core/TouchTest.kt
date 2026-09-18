package app.touch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchTest {
    @Test
    fun reportsDurationAndSilence() {
        val silent = Touch(samplePeriodMillis = 5, amplitudes = listOf(0, 0, 0))
        assertEquals(15, silent.durationMillis)
        assertTrue(silent.isSilent)
        assertFalse(Touch(amplitudes = listOf(0, 1)).isSilent)
    }

    @Test
    fun rejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) {
            Touch(samplePeriodMillis = 0, amplitudes = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            Touch(amplitudes = listOf(256))
        }
    }
}
