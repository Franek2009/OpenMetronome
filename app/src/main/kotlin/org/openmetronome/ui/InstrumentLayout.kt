package org.openmetronome.ui

/** Available width and keyboard-independent window height, not brand or physical pixels. */
enum class InstrumentLayout(val tablet: Boolean) {
    PHONE(false), PHONE_WIDE(false), TABLET_PORTRAIT(true), TABLET_LANDSCAPE(true);

    companion object {
        fun choose(widthDp: Float, heightDp: Float): InstrumentLayout {
            require(widthDp.isFinite() && heightDp.isFinite() && widthDp >= 0 && heightDp >= 0)
            return when {
                widthDp < 600 -> PHONE
                heightDp < 480 -> PHONE_WIDE
                heightDp >= widthDp -> TABLET_PORTRAIT
                else -> TABLET_LANDSCAPE
            }
        }
    }
}
