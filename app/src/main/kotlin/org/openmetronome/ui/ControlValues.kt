package org.openmetronome.ui

import kotlin.math.roundToInt

/** Discrete sliders can deliver 39.999996 for their nominal 40% stop. */
fun probabilityFromSlider(value: Float): Int = value.roundToInt().coerceIn(0, 100)
