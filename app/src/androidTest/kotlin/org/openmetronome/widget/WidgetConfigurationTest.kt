package org.openmetronome.widget

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MusicActivityKind

class WidgetConfigurationTest {
    @Test fun instanceDefaultsIsolationDeletionAndOverlappingRestores() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = "widget-config-test-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences(fixture, mode)
        }
        try {
            val store = WidgetConfigurationStore(isolated)
            assertEquals(WidgetStatisticsScope.METRONOME, store.get(1).scope)
            val first = WidgetConfiguration(scope = WidgetStatisticsScope.MUSIC, instrument = "Kontrabas")
            val second = WidgetConfiguration(category = MusicActivityKind.LESSON, durationMinutes = 45)
            store.put(1, first); store.put(2, second)
            store.remap(intArrayOf(1, 2), intArrayOf(2, 1))
            assertEquals(second, store.get(1)); assertEquals(first, store.get(2))
            store.remove(intArrayOf(1))
            assertEquals(WidgetConfiguration(), store.get(1)); assertEquals(first, store.get(2))
            assertEquals(WidgetConfiguration(), WidgetConfiguration.decode("{invalid"))
        } finally { context.deleteSharedPreferences(fixture) }
    }
}
