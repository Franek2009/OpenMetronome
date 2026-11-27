package org.openmetronome.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openmetronome.ui.theme.OpenMetronomeTheme
import org.openmetronome.domain.model.MusicActivityKind

/** Launcher configuration and the explicit in-widget reconfiguration affordance share this screen. */
class WidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val manager = AppWidgetManager.getInstance(this)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val provider = manager.getAppWidgetInfo(id)?.provider
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || provider?.packageName != packageName ||
            provider.className !in setOf(StatisticsWidget::class.java.name, ActivityTemplateWidget::class.java.name, ActivityShortcutsWidget::class.java.name)) { finish(); return }
        val initial = WidgetConfigurationStore(this).get(id)
        if (provider.className != StatisticsWidget::class.java.name) {
            val count = if (provider.className == ActivityShortcutsWidget::class.java.name) 4 else 1
            setContent { OpenMetronomeTheme { ShortcutConfiguration(initial, count, { finish() }) { config ->
                val saved = withContext(Dispatchers.IO) { runCatching { WidgetConfigurationStore(this@WidgetConfigureActivity).put(id, config) }.isSuccess }
                if (saved) {
                    runCatching { WidgetHost.refresh(applicationContext) }
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                }
                saved
            } } }
            return
        }
        setContent {
            OpenMetronomeTheme {
                var music by rememberSaveable { mutableStateOf(initial.scope == WidgetStatisticsScope.MUSIC) }
                var streak by rememberSaveable { mutableStateOf(initial.showStreak) }
                var week by rememberSaveable { mutableStateOf(initial.showWeek) }
                var active by rememberSaveable { mutableStateOf(initial.showActiveDays) }
                var chart by rememberSaveable { mutableStateOf(initial.showChart) }
                var saving by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Statystyki widgetu", style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!music, { music = false }, label = { Text("Metronom") })
                        FilterChip(music, { music = true }, label = { Text("Cała aktywność") })
                    }
                    @Composable fun option(label: String, checked: Boolean, change: (Boolean) -> Unit) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, Modifier.weight(1f)); Switch(checked, change)
                        }
                    }
                    option("Seria", streak) { streak = it }
                    option("Czas tygodniowy", week) { week = it }
                    option("Aktywne dni i cel", active) { active = it }
                    option("Wykres tygodnia", chart) { chart = it }
                    if (error) Text("Nie udało się zapisać konfiguracji.", color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ finish() }, enabled = !saving) { Text("Anuluj") }
                        Button(onClick = {
                            saving = true; error = false
                            val config = initial.copy(scope = if (music) WidgetStatisticsScope.MUSIC else WidgetStatisticsScope.METRONOME,
                                showStreak = streak, showWeek = week, showActiveDays = active, showChart = chart)
                            lifecycleScope.launch {
                                val saved = withContext(Dispatchers.IO) { runCatching { WidgetConfigurationStore(this@WidgetConfigureActivity).put(id, config) }.isSuccess }
                                if (saved) {
                                    runCatching { WidgetHost.refresh(applicationContext) }
                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                } else { saving = false; error = true }
                            }
                        }, enabled = !saving) { Text("Zapisz") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutConfiguration(initial: WidgetConfiguration, count: Int, cancel: () -> Unit, save: suspend (WidgetConfiguration) -> Boolean) {
    val defaults = remember(initial, count) { (0 until count).map { index ->
        if (count == 1) WidgetShortcut(initial.category, initial.instrument, initial.durationMinutes)
        else initial.shortcuts.getOrNull(index) ?: defaultWidgetShortcuts()[index]
    } }
    var categories by rememberSaveable { mutableStateOf(defaults.map { it.category.name }.toTypedArray()) }
    var instruments by rememberSaveable { mutableStateOf(defaults.map { it.instrument }.toTypedArray()) }
    var durations by rememberSaveable { mutableStateOf(defaults.map { it.durationMinutes.toString() }.toTypedArray()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val values = (0 until count).map { index ->
        runCatching { WidgetShortcut(MusicActivityKind.valueOf(categories[index]), instruments[index].trim(), durations[index].toInt()) }.getOrNull()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Szablony aktywności", style = MaterialTheme.typography.headlineSmall)
        repeat(count) { index ->
            key(index) {
                if (count > 1) Text("Skrót ${index + 1}", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MusicActivityKind.entries.forEach { item -> FilterChip(categories[index] == item.name, { categories = categories.copyOf().also { it[index] = item.name } }, label = { Text(item.widgetLabel()) }) }
                }
                OutlinedTextField(instruments[index], { value -> instruments = instruments.copyOf().also { it[index] = value } }, label = { Text("Instrument (opcjonalnie)") }, singleLine = true)
                OutlinedTextField(durations[index], { value -> durations = durations.copyOf().also { it[index] = value } }, label = { Text("Czas w minutach") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                if (values[index] == null) Text("Podaj czas od 1 do 10080 minut i instrument do 120 znaków.", color = MaterialTheme.colorScheme.error)
            }
        }
        if (error) Text("Nie udało się zapisać konfiguracji.", color = MaterialTheme.colorScheme.error)
        TextButton(cancel, enabled = !saving) { Text("Anuluj") }
        Button(onClick = {
            val result = values.filterNotNull()
            if (result.size == count) {
                val one = result.first()
                saving = true; error = false
                scope.launch {
                    val ok = save(initial.copy(category = one.category, instrument = one.instrument, durationMinutes = one.durationMinutes,
                        shortcuts = if (count == 4) result else emptyList()))
                    if (!ok) { saving = false; error = true }
                }
            }
        }, enabled = !saving && values.all { it != null }) { Text("Zapisz") }
    }
}
