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

    @Test fun statisticsInstancesRespectScopeSelectionSizeAndFailure() {
        val monday = java.time.LocalDate.of(2026, 11, 23)
        val days = (0L..6L).map { WidgetDay(monday.plusDays(it), it * 60_000, it > 2) }
        val sample = WidgetStatistics(600_000, 3, 3_600_000, 4, 5, days)
        instrumentation.runOnMainSync {
            val config = WidgetConfiguration(scope = WidgetStatisticsScope.MUSIC, showStreak = false, showWeek = true)
            val compact = WidgetHost.statisticsViews(context, 1001, config, Result.success(sample), false).apply(context, FrameLayout(context))
            assertEquals("Dzisiaj · cała aktywność", compact.findViewById<TextView>(R.id.widget_statistics_title).text.toString())
            val detail = compact.findViewById<TextView>(R.id.widget_streak).text.toString()
            assertFalse(detail.contains("Seria")); assertTrue(detail.contains("Tydzień")); assertTrue(detail.contains("4/5"))
            assertEquals(View.GONE, compact.findViewById<View>(R.id.widget_week).visibility)
            val expanded = WidgetHost.statisticsViews(context, 1002, config, Result.success(sample), true).apply(context, FrameLayout(context))
            assertEquals(View.VISIBLE, expanded.findViewById<View>(R.id.widget_week).visibility)
            val bars = listOf(R.id.widget_day_0, R.id.widget_day_1, R.id.widget_day_2, R.id.widget_day_3, R.id.widget_day_4, R.id.widget_day_5, R.id.widget_day_6)
            assertEquals(listOf(0, 16, 33, 50, 66, 83, 100), bars.map { expanded.findViewById<android.widget.ProgressBar>(it).progress })
            assertTrue(expanded.findViewById<View>(R.id.widget_day_0).contentDescription.contains("poniedziałek"))
            val goal = expanded.findViewById<android.widget.ProgressBar>(R.id.widget_goal_progress)
            assertEquals(View.VISIBLE, goal.visibility); assertEquals(5, goal.max); assertEquals(4, goal.progress)
            val noGoal = WidgetHost.statisticsViews(context, 1006, config, Result.success(sample.copy(weeklyGoal = null)), true).apply(context, FrameLayout(context))
            assertEquals(View.GONE, noGoal.findViewById<View>(R.id.widget_goal_progress).visibility)
            assertTrue(noGoal.findViewById<TextView>(R.id.widget_streak).text.contains("Aktywne dni: 4"))
            val hidden = WidgetHost.statisticsViews(context, 1007, config.copy(showActiveDays = false, showChart = false), Result.success(sample), true).apply(context, FrameLayout(context))
            assertEquals(View.GONE, hidden.findViewById<View>(R.id.widget_goal_progress).visibility)
            assertEquals(View.GONE, hidden.findViewById<View>(R.id.widget_week).visibility)
            val error = WidgetHost.statisticsViews(context, 1003, WidgetConfiguration(), Result.failure(IllegalStateException()), false).apply(context, FrameLayout(context))
            assertTrue(error.findViewById<TextView>(R.id.widget_stats).text.contains("niedostępne"))
            val loading = WidgetHost.statisticsViews(context, 1004, WidgetConfiguration(), null, false).apply(context, FrameLayout(context))
            assertTrue(loading.findViewById<TextView>(R.id.widget_stats).text.contains("Ładowanie"))
            val empty = WidgetHost.statisticsViews(context, 1005, WidgetConfiguration(), Result.success(sample.copy(todayMs = 0, hasData = false)), false).apply(context, FrameLayout(context))
            assertTrue(empty.findViewById<TextView>(R.id.widget_streak).text.contains("Brak danych"))
        }
    }

    @Test fun shortcutRemoteViewsInflateWithIndependentTemplates() {
        val config = WidgetConfiguration(shortcuts = listOf(
            WidgetShortcut(org.openmetronome.domain.model.MusicActivityKind.LESSON, "Kontrabas", 45),
            WidgetShortcut(org.openmetronome.domain.model.MusicActivityKind.CONCERT, "", 60)))
        instrumentation.runOnMainSync {
            val mini = WidgetHost.activityViews(context, 1001, AddActivityWidget::class.java, config).apply(context, FrameLayout(context))
            assertNotNull(mini.findViewById<View>(R.id.widget_activity_add))
            val bar = WidgetHost.activityViews(context, 1002, ActivityShortcutsWidget::class.java, config).apply(context, FrameLayout(context))
            assertTrue(bar.findViewById<TextView>(R.id.widget_shortcut_1).text.contains("45"))
            assertTrue(bar.findViewById<TextView>(R.id.widget_shortcut_2).contentDescription.contains("Koncert"))
            assertTrue(bar.findViewById<TextView>(R.id.widget_shortcut_1).contentDescription.contains("Kontrabas"))
        }
    }

    @Test fun compactShortcutBarKeepsTouchTargetsAtLargeFont() {
        instrumentation.runOnMainSync {
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.5f }
            val sized = context.createConfigurationContext(configuration)
            val density = sized.resources.displayMetrics.density
            val view = WidgetHost.activityViews(sized, 1005, ActivityShortcutsWidget::class.java, WidgetConfiguration()).apply(sized, FrameLayout(sized))
            val width = (250 * density).toInt(); val height = (48 * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            for (id in listOf(R.id.widget_shortcut_1, R.id.widget_shortcut_2, R.id.widget_shortcut_3, R.id.widget_shortcut_4, R.id.widget_activity_configure)) {
                val target = view.findViewById<TextView>(id)
                assertTrue("Touch target $id", target.width >= (48 * density).toInt() && target.height >= (48 * density).toInt())
                for (line in 0 until target.lineCount) assertEquals("Clipped label $id", 0, target.layout.getEllipsisCount(line))
            }
        }
    }

    @Test fun compactStatisticsKeepsConfiguredInformationAtLargeFont() {
        instrumentation.runOnMainSync {
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.5f }
            val sized = context.createConfigurationContext(configuration)
            val density = sized.resources.displayMetrics.density
            val monday = java.time.LocalDate.of(2026, 11, 23)
            val sample = WidgetStatistics(600_000, 3, 3_600_000, 4, 5, (0L..6L).map { WidgetDay(monday.plusDays(it), 0, false) })
            val view = WidgetHost.statisticsViews(sized, 1010, WidgetConfiguration(scope = WidgetStatisticsScope.MUSIC), Result.success(sample), false)
                .apply(sized, FrameLayout(sized))
            val width = (250 * density).toInt(); val height = (160 * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val detail = view.findViewById<TextView>(R.id.widget_streak)
            assertTrue(detail.text.contains("Seria") && detail.text.contains("Tydzień") && detail.text.contains("4/5"))
            for (line in 0 until detail.lineCount) assertEquals("Clipped statistics", 0, detail.layout.getEllipsisCount(line))
            val bounds = android.graphics.Rect(); detail.getDrawingRect(bounds)
            (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(detail, bounds)
            assertTrue("Clipped statistics bounds: $bounds", bounds.bottom <= height)
        }
    }

    @Test fun expandedStatisticsFitsMinimumWidthAndLargeFont() {
        instrumentation.runOnMainSync {
            val sized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.5f })
            val density = sized.resources.displayMetrics.density
            val monday = java.time.LocalDate.of(2026, 11, 23)
            val sample = WidgetStatistics(600_000, 3, 3_600_000, 4, 5, (0L..6L).map { WidgetDay(monday.plusDays(it), it * 60_000, true) })
            val view = WidgetHost.statisticsViews(sized, 1011, WidgetConfiguration(scope = WidgetStatisticsScope.MUSIC), Result.success(sample), true).apply(sized, FrameLayout(sized))
            val width = (250 * density).toInt(); val height = (190 * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val chart = view.findViewById<View>(R.id.widget_week)
            val goal = view.findViewById<View>(R.id.widget_goal_progress)
            assertEquals(View.VISIBLE, chart.visibility); assertEquals(View.VISIBLE, goal.visibility)
            for (id in listOf(R.id.widget_day_0, R.id.widget_day_1, R.id.widget_day_2, R.id.widget_day_3, R.id.widget_day_4, R.id.widget_day_5, R.id.widget_day_6)) {
                val bar = view.findViewById<View>(id)
                assertTrue("Bar has no visible area: $id size=${bar.width}x${bar.height}, chart=${chart.width}x${chart.height}, detail=${view.findViewById<View>(R.id.widget_streak).height}, bpm=${view.findViewById<View>(R.id.widget_stats).height}", bar.width > 0 && bar.height >= (8 * density).toInt())
                val bounds = android.graphics.Rect(); bar.getDrawingRect(bounds)
                (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(bar, bounds)
                assertTrue("Bar outside widget: $bounds", bounds.left >= 0 && bounds.right <= width && bounds.bottom <= height)
            }
            val detail = view.findViewById<TextView>(R.id.widget_streak)
            for (line in 0 until detail.lineCount) assertEquals("Clipped statistics", 0, detail.layout.getEllipsisCount(line))
            if (InstrumentationRegistry.getArguments().getString("captureUi") == "true") {
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                java.io.File(context.cacheDir, "ui-v012-widget-statistics-min-font150.png").outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun selectedPercentPendingTempoAndResizeUseActualRemoteViews() {
        instrumentation.runOnMainSync {
            val value = WidgetPlayback("120", 120, 50, "Gra", true, true)
            val view = WidgetHost.controlViews(context, value, true, false).apply(context, FrameLayout(context))
            assertEquals("120 BPM", view.findViewById<TextView>(R.id.widget_bpm).text.toString())
            assertEquals("50%", view.findViewById<TextView>(R.id.widget_50).text.toString())
            assertTrue(view.findViewById<TextView>(R.id.widget_50).contentDescription.contains("wybrane"))
            assertEquals("120 × 50% →", view.findViewById<TextView>(R.id.widget_base).text.toString())
            assertTrue(view.findViewById<TextView>(R.id.widget_base).contentDescription.contains("oczekuje"))
            assertEquals(View.GONE, view.findViewById<View>(R.id.widget_status).visibility)
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
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_status).visibility)
            val ready = WidgetHost.controlViews(context, WidgetPlayback("120", 120, 100, "Zatrzymany", false, false), true, false)
                .apply(context, FrameLayout(context))
            assertEquals(View.GONE, ready.findViewById<View>(R.id.widget_base).visibility)
            assertEquals(View.GONE, ready.findViewById<View>(R.id.widget_status).visibility)
            assertTrue(view.findViewById<TextView>(R.id.widget_status).text.contains("aplikację"))
        }
    }
    @Test fun statisticsInflatesAndAllReceiversArePrivate() {
        instrumentation.runOnMainSync {
            val view = android.widget.RemoteViews(context.packageName, R.layout.widget_statistics).apply(context, FrameLayout(context))
            assertNotNull(view.findViewById<View>(R.id.widget_stats))
        }
        for (type in listOf(MiniWidget::class.java, ControlWidget::class.java, StatisticsWidget::class.java,
            AddActivityWidget::class.java, ActivityTemplateWidget::class.java, ActivityShortcutsWidget::class.java, WidgetCommandReceiver::class.java)) {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getReceiverInfo(ComponentName(context, type), PackageManager.GET_META_DATA)
            assertFalse(info.exported)
            if (type != WidgetCommandReceiver::class.java) assertTrue(info.metaData.containsKey("android.appwidget.provider"))
        }
    }
    @Test fun minimumSizesKeepTouchTargetsInsidePanelWithLargeFont() {
        instrumentation.runOnMainSync {
            for (scale in listOf(1f, 1.5f)) {
                val configuration = android.content.res.Configuration(context.resources.configuration).apply { fontScale = scale }
                val sized = context.createConfigurationContext(configuration)
                val density = sized.resources.displayMetrics.density
                for (control in listOf(false, true)) {
                    val value = WidgetPlayback("30.75", 123, 25, "Zatrzymany", false, false)
                    val view = WidgetHost.controlViews(sized, value, control, false).apply(sized, FrameLayout(sized))
                    val w = ((if (control) 250 else 110) * density).toInt(); val h = (160 * density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, w, h)
                    for (id in if (control) listOf(R.id.widget_play, R.id.widget_100, R.id.widget_75, R.id.widget_50, R.id.widget_25) else listOf(R.id.widget_play)) {
                        val button = view.findViewById<View>(id)
                        val bounds = android.graphics.Rect()
                        button.getDrawingRect(bounds)
                        (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(button, bounds)
                        assertTrue("Clipped control $id font=$scale: $bounds", bounds.top >= 0 && bounds.bottom <= h)
                        assertTrue("Small touch target $id", button.height >= (48 * density).toInt())
                        assertTrue("Narrow touch target $id", button.width >= (48 * density).toInt())
                        assertTrue("Clipped horizontally $id font=$scale: $bounds", bounds.left >= 0 && bounds.right <= w)
                    }
                }
            }
        }
    }

    @Test fun launcherFailureCannotEscapeIntoPlaybackPublisher() {
        var lookups = 0
        val brokenLauncher = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSystemService(name: String): Any? {
                if (name == android.content.Context.APPWIDGET_SERVICE) { lookups++; error("Injected launcher failure") }
                return super.getSystemService(name)
            }
        }
        instrumentation.runOnMainSync {
            WidgetHost.publishSnapshot(brokenLauncher, WidgetModel.playback(org.openmetronome.audio.MetronomeState()))
            WidgetHost.widgetsChanged(brokenLauncher)
        }
        assertTrue("Both failing launcher boundaries must actually be exercised", lookups >= 2)
    }

    @Test fun captureActualRemoteViewsPreviewsWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        instrumentation.runOnMainSync {
            val value = WidgetPlayback("90", 120, 75, "Zatrzymany", false, false)
            val stats = android.widget.RemoteViews(context.packageName, R.layout.widget_statistics).apply {
                setTextViewText(R.id.widget_stats, "42 min 00 s")
                setTextViewText(R.id.widget_streak, "Seria: 3 dni")
            }
            val monday = java.time.LocalDate.of(2026, 11, 23)
            val sample = WidgetStatistics(600_000, 3, 3_600_000, 4, 5,
                (0L..6L).map { WidgetDay(monday.plusDays(it), it * 60_000, it > 2) })
            data class Capture(val name: String, val remote: android.widget.RemoteViews, val width: Int, val height: Int)
            for ((name, remote, width, height) in listOf(
                Capture("widget-mini", WidgetHost.controlViews(context, value, false, false), 160, 180),
                Capture("widget-control", WidgetHost.controlViews(context, value, true, false), 340, 180),
                Capture("widget-statistics", stats, 340, 180),
                Capture("widget-statistics-compact", WidgetHost.statisticsViews(context, 501, WidgetConfiguration(scope = WidgetStatisticsScope.MUSIC), Result.success(sample), false), 340, 160),
                Capture("widget-statistics-expanded", WidgetHost.statisticsViews(context, 502, WidgetConfiguration(scope = WidgetStatisticsScope.MUSIC), Result.success(sample), true), 340, 250),
                Capture("widget-activity-add", WidgetHost.activityViews(context, 503, AddActivityWidget::class.java, WidgetConfiguration()), 70, 70),
                Capture("widget-activity-template", WidgetHost.activityViews(context, 504, ActivityTemplateWidget::class.java, WidgetConfiguration()), 160, 160),
                Capture("widget-activity-shortcuts", WidgetHost.activityViews(context, 505, ActivityShortcutsWidget::class.java, WidgetConfiguration()), 340, 70))) {
                val view = remote.apply(context, FrameLayout(context))
                val density = context.resources.displayMetrics.density
                val w = (width * density).toInt(); val h = (height * density).toInt()
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
