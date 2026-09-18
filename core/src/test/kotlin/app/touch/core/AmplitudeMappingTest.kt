package app.touch.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AmplitudeMappingTest {
    @Test
    fun mapsTopMiddleAndBottom() {
        assertEquals(255, amplitudeAt(0f, 100f))
        assertEquals(128, amplitudeAt(50f, 100f))
        assertEquals(1, amplitudeAt(100f, 100f))
    }

    @Test
    fun clampsCoordinatesOutsideSurface() {
        assertEquals(255, amplitudeAt(-20f, 100f))
        assertEquals(1, amplitudeAt(120f, 100f))
    }
}
