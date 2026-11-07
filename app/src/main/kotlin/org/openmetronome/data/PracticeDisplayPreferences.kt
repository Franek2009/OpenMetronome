package org.openmetronome.data

/** Display choices are independent from a preset's rhythm and tempo. */
data class PracticeDisplayPreferences(val keepFullscreenAwake: Boolean = false, val reduceMotion: Boolean = false)
