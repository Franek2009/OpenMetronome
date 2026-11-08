package org.openmetronome.widget

import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.R

/** Actual RemoteViews inflation; does not start playback or depend on launcher bind permission. */
class WidgetResourcesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun selectedPercentPendingTempoAndResizeUseActualRemoteViews() {
        instrumentation.runOnMainSync {
            val value = WidgetPlayback("120", 120, 50, "Gra", true, true)
            val view = WidgetHost.controlViews(context, value, true, false).apply(context, FrameLayout(context))
            assertEquals("120 BPM", view.findViewById<TextView>(R.id.widget_bpm).text.toString())
            assertEquals("50% ✓", view.findViewById<TextView>(R.id.widget_50).text.toString())
            assertTrue(view.findViewById<TextView>(R.id.widget_base).text.contains("oczekuje"))
            assertEquals("■ Stop", view.findViewById<TextView>(R.id.widget_play).text.toString())
            assertEquals(View.GONE, view.findViewById<View>(R.id.widget_steps).visibility)
            val expanded = WidgetHost.controlViews(context, value, true, true).apply(context, FrameLayout(context))
            assertEquals(View.VISIBLE, expanded.findViewById<View>(R.id.widget_steps).visibility)
        }
    }
    @Test fun miniHasNoExtraControlsAndUnknownDataNeverClaimsPlayback() {
        instrumentation.runOnMainSync {
            val view = WidgetHost.controlViews(context, null, false, false).apply(context, FrameLayout(context))
            assertEquals("— BPM", view.findViewById<TextView>(R.id.widget_bpm).text.toString())
            assertNull(view.findViewById<View>(R.id.widget_50))
            assertTrue(view.findViewById<TextView>(R.id.widget_status).text.contains("aplikację"))
        }
    }
    @Test fun statisticsInflatesAndAllReceiversArePrivate() {
        instrumentation.runOnMainSync {
            val view = android.widget.RemoteViews(context.packageName, R.layout.widget_statistics).apply(context, FrameLayout(context))
            assertNotNull(view.findViewById<View>(R.id.widget_stats))
        }
        for (type in listOf(MiniWidget::class.java, ControlWidget::class.java, StatisticsWidget::class.java, WidgetCommandReceiver::class.java)) {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getReceiverInfo(ComponentName(context, type), PackageManager.GET_META_DATA)
            assertFalse(info.exported)
            if (type != WidgetCommandReceiver::class.java) assertTrue(info.metaData.containsKey("android.appwidget.provider"))
        }
    }
    @Test fun captureActualRemoteViewsPreviewsWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        instrumentation.runOnMainSync {
            val value = WidgetPlayback("90", 120, 75, "Zatrzymany", false, false)
            val stats = android.widget.RemoteViews(context.packageName, R.layout.widget_statistics).apply {
                setTextViewText(R.id.widget_stats, "42 min 00 s")
                setTextViewText(R.id.widget_streak, "Seria: 3 dni")
                setTextViewText(R.id.widget_week, "Pn ●  Wt ●  Śr ●  Cz ○  Pt ○  So ○  Nd ○")
            }
            for ((name, remote, width) in listOf(
                Triple("widget-mini", WidgetHost.controlViews(context, value, false, false), 160),
                Triple("widget-control", WidgetHost.controlViews(context, value, true, false), 340),
                Triple("widget-statistics", stats, 340))) {
                val view = remote.apply(context, FrameLayout(context))
                val density = context.resources.displayMetrics.density
                val w = (width * density).toInt(); val h = (180 * density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
                val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                java.io.File(context.cacheDir, "ui-v012-$name.png").outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        }
    }

}
