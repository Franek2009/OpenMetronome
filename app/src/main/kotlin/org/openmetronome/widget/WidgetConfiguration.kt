package org.openmetronome.widget

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import org.openmetronome.domain.model.MusicActivityKind

internal enum class WidgetStatisticsScope { METRONOME, MUSIC }

internal data class WidgetShortcut(val category: MusicActivityKind = MusicActivityKind.INDIVIDUAL_PRACTICE,
    val instrument: String = "", val durationMinutes: Int = 30) {
    init { require(durationMinutes in 1..10_080); require(instrument.length <= 120) }
}

internal fun MusicActivityKind.widgetLabel(): String = when (this) {
    MusicActivityKind.LESSON -> "Lekcja"
    MusicActivityKind.INDIVIDUAL_PRACTICE -> "Ćwiczenie"
    MusicActivityKind.ENSEMBLE_REHEARSAL -> "Próba"
    MusicActivityKind.CONCERT -> "Koncert"
    MusicActivityKind.OTHER -> "Inne"
}

internal fun MusicActivityKind.compactWidgetLabel(): String = when (this) {
    MusicActivityKind.LESSON -> "Lek."
    MusicActivityKind.INDIVIDUAL_PRACTICE -> "Ćw."
    MusicActivityKind.ENSEMBLE_REHEARSAL -> "Pró."
    MusicActivityKind.CONCERT -> "Kon."
    MusicActivityKind.OTHER -> "Inne"
}

internal fun defaultWidgetShortcuts(): List<WidgetShortcut> = listOf(
    WidgetShortcut(MusicActivityKind.INDIVIDUAL_PRACTICE, durationMinutes = 30),
    WidgetShortcut(MusicActivityKind.LESSON, durationMinutes = 45),
    WidgetShortcut(MusicActivityKind.ENSEMBLE_REHEARSAL, durationMinutes = 60),
    WidgetShortcut(MusicActivityKind.OTHER, durationMinutes = 15),
)

internal data class WidgetConfiguration(
    val scope: WidgetStatisticsScope = WidgetStatisticsScope.METRONOME,
    val showStreak: Boolean = true,
    val showWeek: Boolean = true,
    val showActiveDays: Boolean = true,
    val showChart: Boolean = true,
    val category: MusicActivityKind = MusicActivityKind.INDIVIDUAL_PRACTICE,
    val instrument: String = "",
    val durationMinutes: Int = 30,
    val shortcuts: List<WidgetShortcut> = emptyList(),
) {
    init { require(durationMinutes in 1..10_080); require(instrument.length <= 120); require(shortcuts.size <= 4) }

    fun encode(): String = JSONObject().put("scope", scope.name).put("streak", showStreak)
        .put("week", showWeek).put("active", showActiveDays).put("chart", showChart)
        .put("category", category.name).put("instrument", instrument).put("duration", durationMinutes)
        .put("shortcuts", JSONArray().also { array -> shortcuts.forEach { shortcut -> array.put(JSONObject()
            .put("category", shortcut.category.name).put("instrument", shortcut.instrument).put("duration", shortcut.durationMinutes)) } }).toString()

    companion object {
        fun decode(text: String?): WidgetConfiguration {
            if (text == null) return WidgetConfiguration()
            return runCatching {
                val json = JSONObject(text)
                WidgetConfiguration(
                    WidgetStatisticsScope.valueOf(json.optString("scope", WidgetStatisticsScope.METRONOME.name)),
                    json.optBoolean("streak", true), json.optBoolean("week", true),
                    json.optBoolean("active", true), json.optBoolean("chart", true),
                    MusicActivityKind.valueOf(json.optString("category", MusicActivityKind.INDIVIDUAL_PRACTICE.name)),
                    json.optString("instrument", ""), json.optInt("duration", 30),
                    json.optJSONArray("shortcuts")?.let { array -> (0 until array.length()).map { index ->
                        val item = array.getJSONObject(index)
                        WidgetShortcut(MusicActivityKind.valueOf(item.getString("category")), item.optString("instrument", ""), item.getInt("duration"))
                    } }.orEmpty(),
                )
            }.getOrDefault(WidgetConfiguration())
        }
    }
}

/** Each app package has its own private preferences, including PROD and DEV. */
internal class WidgetConfigurationStore(context: Context) {
    private val prefs = context.getSharedPreferences("widget-config-v1", Context.MODE_PRIVATE)
    fun get(id: Int): WidgetConfiguration = WidgetConfiguration.decode(prefs.getString(id.toString(), null))
    fun put(id: Int, config: WidgetConfiguration) { require(id > 0); check(prefs.edit().putString(id.toString(), config.encode()).commit()) }
    fun remove(ids: IntArray) { check(prefs.edit().also { editor -> ids.forEach { editor.remove(it.toString()) } }.commit()) }

    /** Snapshot before editing so crossed or overlapping old/new IDs cannot overwrite input. */
    fun remap(oldIds: IntArray, newIds: IntArray) {
        require(oldIds.size == newIds.size)
        require(oldIds.all { it > 0 } && newIds.all { it > 0 })
        require(oldIds.toSet().size == oldIds.size && newIds.toSet().size == newIds.size)
        val saved = oldIds.map { prefs.getString(it.toString(), null) }
        val editor = prefs.edit()
        oldIds.forEach { editor.remove(it.toString()) }
        newIds.forEachIndexed { index, id ->
            val value = saved[index]
            if (value == null) editor.remove(id.toString()) else editor.putString(id.toString(), value)
        }
        check(editor.commit())
    }
}
