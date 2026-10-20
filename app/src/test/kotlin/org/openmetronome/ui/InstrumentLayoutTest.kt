package org.openmetronome.ui

import org.junit.Assert.*
import org.junit.Test

class InstrumentLayoutTest {
    @Test fun `portrait phone and narrow split window stay compact`() {
        assertEquals(InstrumentLayout.PHONE, InstrumentLayout.choose(384f, 650f))
        assertEquals(InstrumentLayout.PHONE, InstrumentLayout.choose(599.99f, 1200f))
    }
    @Test fun `wide phone is not mistaken for a tablet`() {
        assertEquals(InstrumentLayout.PHONE_WIDE, InstrumentLayout.choose(856f, 200f))
        assertEquals(InstrumentLayout.PHONE_WIDE, InstrumentLayout.choose(800f, 479.99f))
    }
    @Test fun `portrait tablet uses full width instrument above lower panels`() {
        assertEquals(InstrumentLayout.TABLET_PORTRAIT, InstrumentLayout.choose(800f, 1020f))
        assertEquals(InstrumentLayout.TABLET_PORTRAIT, InstrumentLayout.choose(600f, 900f))
    }
    @Test fun `landscape tablet uses persistent instrument beside scrollable settings`() {
        assertEquals(InstrumentLayout.TABLET_LANDSCAPE, InstrumentLayout.choose(1280f, 560f))
        assertEquals(InstrumentLayout.TABLET_LANDSCAPE, InstrumentLayout.choose(800f, 480f))
    }
    @Test fun `invalid constraints are rejected`() {
        for (width in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { InstrumentLayout.choose(width, 800f) }
        }
    }
}
