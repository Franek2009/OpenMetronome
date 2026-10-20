package org.openmetronome.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlValuesTest {
    @Test fun `every discrete percentage survives float representation including forty`() {
        for (percent in 0..100) for (epsilon in listOf(-.00001f, 0f, .00001f)) {
            assertEquals(percent, probabilityFromSlider(percent + epsilon))
        }
        assertEquals(40, probabilityFromSlider(39.999996f))
    }
    @Test fun `fractional slider input rounds instead of truncating and clamps boundaries`() {
        assertEquals(40, probabilityFromSlider(39.6f))
        assertEquals(39, probabilityFromSlider(39.4f))
        assertEquals(0, probabilityFromSlider(-2f))
        assertEquals(100, probabilityFromSlider(101f))
    }
}
