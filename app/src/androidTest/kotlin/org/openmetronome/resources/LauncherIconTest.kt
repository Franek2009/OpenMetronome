package org.openmetronome.resources

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.R
import kotlin.math.hypot

/** Resource-only checks: no Activity, playback, focus, vibration or application storage writes. */
class LauncherIconTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(drawable: Drawable): Bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).also {
        drawable.setBounds(0, 0, it.width, it.height)
        drawable.draw(Canvas(it))
    }

    @Test fun launcherResolvesAdaptiveLayersAndThemedMaskOnSupportedAndroid() {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("API 26+ must select the adaptive resource instead of the legacy PNG", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.foreground)
        val background = render(icon.background)
        try {
            for (point in listOf(0 to 0, 216 to 216, 431 to 431))
                assertEquals("Background must be opaque uniform green", Color.GREEN, background.getPixel(point.first, point.second))
        } finally { background.recycle() }
        if (Build.VERSION.SDK_INT >= 33) assertNotNull("Themed icons need their own transparent silhouette", icon.monochrome)
        val expectedLabel = when (context.packageName) {
            "org.openmetronome.debug" -> "OpenMetronome"
            "org.openmetronome.dev.debug" -> "OpenMetronome DEV"
            else -> error("Unexpected instrumentation target: ${context.packageName}")
        }
        assertEquals(expectedLabel, context.applicationInfo.loadLabel(context.packageManager).toString())
    }

    @Test fun silhouettesShareAlphaAndKeepAllDetailsInsideTheSafeCircle() {
        val foreground = render(context.getDrawable(R.drawable.ic_launcher_foreground)!!)
        val mono = render(context.getDrawable(R.drawable.ic_launcher_monochrome)!!)
        try {
            val dev = context.packageName == "org.openmetronome.dev.debug"
            var ink = 0
            var letterCutouts = 0
            for (y in 0 until foreground.height) for (x in 0 until foreground.width) {
                val pixel = foreground.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                // Android's black/white rasterization may round antialiased alpha by 1/255.
                val monoAlpha = Color.alpha(mono.getPixel(x, y))
                val badge = dev && x in 120 until 312 && y in 232 until 304
                if (!badge) assertTrue("Monochrome silhouette differs outside the badge at $x,$y: $alpha vs $monoAlpha",
                    kotlin.math.abs(alpha - monoAlpha) <= 1)
                else {
                    assertEquals("Color badge must be opaque", 255, alpha)
                    if (monoAlpha == 0) letterCutouts++
                }
                if (alpha > 0) {
                    ink++
                    if (!badge) assertEquals("Artwork outside DEV badge must remain black", 0, pixel and 0x00ffffff)
                    else {
                        assertEquals(0, Color.blue(pixel))
                        assertTrue("Badge must contain only amber/black including antialiasing",
                            kotlin.math.abs(179 * Color.red(pixel) - 255 * Color.green(pixel)) <= 256)
                    }
                }
                if (alpha > 0 || monoAlpha > 0) assertTrue("Artwork clipped outside the 66dp safe circle at $x,$y",
                    hypot(x + .5 - 216, y + .5 - 216) <= 33 * 4 + 1)
            }
            if (dev) {
                assertTrue("Missing themed DEV lettering", letterCutouts > 100)
                assertEquals(0xffffb300.toInt(), foreground.getPixel(129, 241))
                assertEquals(255, Color.alpha(mono.getPixel(129, 241)))
                for ((x, y) in listOf(145 to 269, 197 to 269, 266 to 281)) {
                    assertEquals("DEV letter must be black", Color.BLACK, foreground.getPixel(x, y))
                    assertEquals("Themed letter must be a transparent cutout", 0, Color.alpha(mono.getPixel(x, y)))
                }
            }
            assertTrue("Missing or fully opaque square silhouette", ink in 1000 until foreground.width * foreground.height / 2)
        } finally { foreground.recycle(); mono.recycle() }
    }
}
