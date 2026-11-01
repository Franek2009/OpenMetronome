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
        assertEquals("OpenMetronome", context.applicationInfo.loadLabel(context.packageManager).toString())
    }

    @Test fun silhouettesShareAlphaAndKeepAllDetailsInsideTheSafeCircle() {
        val foreground = render(context.getDrawable(R.drawable.ic_launcher_foreground)!!)
        val mono = render(context.getDrawable(R.drawable.ic_launcher_monochrome)!!)
        try {
            var ink = 0
            for (y in 0 until foreground.height) for (x in 0 until foreground.width) {
                val pixel = foreground.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                // Android's black/white rasterization may round antialiased alpha by 1/255.
                val monoAlpha = Color.alpha(mono.getPixel(x, y))
                assertTrue("Monochrome silhouette differs at $x,$y: $alpha vs $monoAlpha",
                    kotlin.math.abs(alpha - monoAlpha) <= 1)
                if (alpha > 0) {
                    ink++
                    assertEquals("Foreground must contain only black symbols", 0, pixel and 0x00ffffff)
                    assertTrue("Artwork clipped outside the 66dp safe circle at $x,$y",
                        hypot(x + .5 - 216, y + .5 - 216) <= 33 * 4 + 1)
                }
            }
            assertTrue("Missing or fully opaque square silhouette", ink in 1000 until foreground.width * foreground.height / 2)
        } finally { foreground.recycle(); mono.recycle() }
    }
}
