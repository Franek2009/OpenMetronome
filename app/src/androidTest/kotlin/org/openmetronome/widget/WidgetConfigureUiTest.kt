package org.openmetronome.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.openmetronome.domain.model.MusicActivityKind

class WidgetConfigureUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun statisticsReconfigurationPersistsAndCancelDoesNotOverwrite() {
        val host = AppWidgetHost(context, (UUID.randomUUID().hashCode() and 0x3fffffff) + 1)
        val id = host.allocateAppWidgetId()
        val store = WidgetConfigurationStore(context)
        try {
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id, ComponentName(context, StatisticsWidget::class.java)))
            fun launch() = ActivityScenario.launch<WidgetConfigureActivity>(Intent(context, WidgetConfigureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            launch().use { scenario ->
                compose.onNodeWithText("Cała aktywność").performClick().assertIsSelected()
                scenario.recreate()
                compose.onNodeWithText("Cała aktywność").assertIsSelected()
                compose.onNodeWithText("Zapisz").performScrollTo().performClick()
                compose.waitUntil(10000) { store.get(id).scope == WidgetStatisticsScope.MUSIC }
            }
            launch().use {
                compose.onNodeWithText("Metronom").performClick()
                compose.onNodeWithText("Anuluj").performScrollTo().performClick()
            }
            assertEquals(WidgetStatisticsScope.MUSIC, store.get(id).scope)
        } finally { host.deleteAppWidgetId(id); store.remove(intArrayOf(id)) }
    }

    @Test fun shortcutBarStoresFourIndependentTemplates() {
        val host = AppWidgetHost(context, (UUID.randomUUID().hashCode() and 0x3fffffff) + 1)
        val id = host.allocateAppWidgetId()
        val store = WidgetConfigurationStore(context)
        try {
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id, ComponentName(context, ActivityShortcutsWidget::class.java)))
            ActivityScenario.launch<WidgetConfigureActivity>(Intent(context, WidgetConfigureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)).use { scenario ->
                compose.onAllNodesWithText("Koncert")[1].performScrollTo().performClick()
                compose.onAllNodesWithText("Czas w minutach")[1].performScrollTo().performTextReplacement("90")
                scenario.recreate()
                compose.onAllNodesWithText("Czas w minutach")[1].performScrollTo().assertTextContains("90")
                compose.onNodeWithText("Zapisz").performScrollTo().performClick()
                compose.waitUntil(10000) { store.get(id).shortcuts.size == 4 }
            }
            val config = store.get(id)
            assertEquals(MusicActivityKind.CONCERT, config.shortcuts[1].category)
            assertEquals(90, config.shortcuts[1].durationMinutes)
            assertEquals(30, config.shortcuts[0].durationMinutes)
        } finally { host.deleteAppWidgetId(id); store.remove(intArrayOf(id)) }
    }
}
