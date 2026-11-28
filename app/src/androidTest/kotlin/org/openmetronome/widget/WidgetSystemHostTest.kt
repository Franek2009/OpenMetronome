package org.openmetronome.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.openmetronome.TestApplication
import org.openmetronome.domain.model.MusicActivityDetails
import org.openmetronome.domain.model.MusicActivityKind

/** Exercises Android's widget service and host; this is not a Samsung launcher test. */
class WidgetSystemHostTest {
    @Test fun musicWidgetRefreshesAfterAddEditAndDelete() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as TestApplication
        val data = application.data
        val priorSettings = runBlocking(Dispatchers.IO) { data.ready.await(); data.settings.musicStatistics.first() }
        val offset = java.time.ZoneOffset.ofHours(12 - java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).hour)
        runBlocking(Dispatchers.IO) {
            data.database.clearAllTables()
            data.settings.saveMusicStatistics(org.openmetronome.data.MusicStatisticsPreferences(zone = offset))
        }
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, (UUID.randomUUID().hashCode() and 0x3fffffff) + 1)
        val id = host.allocateAppWidgetId()
        val store = WidgetConfigurationStore(context)
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, StatisticsWidget::class.java)))
            store.put(id, WidgetConfiguration(scope = WidgetStatisticsScope.MUSIC))
            lateinit var view: android.appwidget.AppWidgetHostView
            instrumentation.runOnMainSync {
                host.startListening()
                view = host.createView(context, id, manager.getAppWidgetInfo(id))
                view.setAppWidget(id, manager.getAppWidgetInfo(id))
            }
            runBlocking { WidgetHost.refresh(context) }
            fun waitForText(expected: String) {
                val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                var found = false
                while (!found && android.os.SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        found = view.findViewById<android.widget.TextView>(org.openmetronome.R.id.widget_stats)?.text?.contains(expected) == true
                    }
                    if (!found) android.os.SystemClock.sleep(50)
                }
                assertTrue("Widget did not display $expected", found)
            }
            val now = System.currentTimeMillis()
            val zone = offset.id
            val details = MusicActivityDetails.manual(MusicActivityKind.LESSON, now - 600_000, now - 300_000, zone)
            data.addMusicActivity(details)
            runBlocking { data.awaitWrites() }
            waitForText("5 min")
            val activity = runBlocking { data.musicActivities.first().single() }
            val backup = java.io.ByteArrayOutputStream()
            data.exportBackup { backup }
            runBlocking { data.awaitBackup() }
            assertTrue(backup.size() > 0)
            data.editMusicActivity(activity.id, MusicActivityDetails.manual(MusicActivityKind.LESSON, now - 900_000, now - 300_000, zone))
            runBlocking { data.awaitWrites() }
            waitForText("10 min")
            data.importBackup({ java.io.ByteArrayInputStream(backup.toByteArray()) }, {})
            runBlocking { data.awaitWrites() }
            waitForText("5 min")
            data.deleteMusicActivity(activity.id)
            runBlocking { data.awaitWrites() }
            waitForText("0 s")
        } finally {
            instrumentation.runOnMainSync { host.stopListening() }
            host.deleteAppWidgetId(id)
            store.remove(intArrayOf(id))
            runBlocking(Dispatchers.IO) { data.settings.saveMusicStatistics(priorSettings ?: org.openmetronome.data.MusicStatisticsPreferences()) }
        }
    }

    @Test fun separateInstancesBindResizeAndDeleteThroughSystemHost() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, (UUID.randomUUID().hashCode() and 0x3fffffff) + 1)
        val first = host.allocateAppWidgetId()
        val second = host.allocateAppWidgetId()
        val add = host.allocateAppWidgetId()
        val bar = host.allocateAppWidgetId()
        val store = WidgetConfigurationStore(context)
        try {
            for ((id, provider) in listOf(first to StatisticsWidget::class.java, second to StatisticsWidget::class.java,
                add to AddActivityWidget::class.java, bar to ActivityShortcutsWidget::class.java)) {
                assertTrue("The isolated emulator must grant widget binding", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, provider)))
                assertEquals(provider.name, manager.getAppWidgetInfo(id).provider.className)
            }
            store.put(first, WidgetConfiguration(scope = WidgetStatisticsScope.METRONOME))
            store.put(second, WidgetConfiguration(scope = WidgetStatisticsScope.MUSIC, showStreak = false))
            manager.updateAppWidgetOptions(first, Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160) })
            manager.updateAppWidgetOptions(second, Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250) })
            assertEquals(160, manager.getAppWidgetOptions(first).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
            assertEquals(250, manager.getAppWidgetOptions(second).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
            assertEquals(WidgetStatisticsScope.METRONOME, store.get(first).scope)
            assertEquals(WidgetStatisticsScope.MUSIC, store.get(second).scope)
            StatisticsWidget().onRestored(context, intArrayOf(first, second), intArrayOf(second, first))
            assertEquals(WidgetStatisticsScope.MUSIC, store.get(first).scope)
            assertEquals(WidgetStatisticsScope.METRONOME, store.get(second).scope)
            assertTrue(manager.getAppWidgetOptions(first).getBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                val view = host.createView(context, second, manager.getAppWidgetInfo(second))
                view.setAppWidget(second, manager.getAppWidgetInfo(second))
                val monday = java.time.LocalDate.of(2026, 11, 23)
                val sample = WidgetStatistics(600_000, 3, 3_600_000, 4, 5,
                    (0L..6L).map { WidgetDay(monday.plusDays(it), it * 60_000, it > 2) })
                view.updateAppWidget(WidgetHost.statisticsViews(context, second,
                    WidgetConfiguration(scope = WidgetStatisticsScope.MUSIC), Result.success(sample), true))
                val density = context.resources.displayMetrics.density
                val width = (340 * density).toInt(); val height = (250 * density).toInt()
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                assertTrue(view.findViewById<android.widget.TextView>(org.openmetronome.R.id.widget_stats).text.contains("10 min"))
                if (InstrumentationRegistry.getArguments().getString("captureUi") == "true") {
                    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(bitmap))
                    java.io.File(context.cacheDir, "ui-v012-widget-system-host.png").outputStream().use {
                        assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                    bitmap.recycle()
                }
            }
        } finally {
            listOf(first, second, add, bar).forEach(host::deleteAppWidgetId)
            store.remove(intArrayOf(first, second, add, bar))
        }
    }
}
