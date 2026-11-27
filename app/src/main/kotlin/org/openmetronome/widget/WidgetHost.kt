package org.openmetronome.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openmetronome.MainActivity
import org.openmetronome.OpenMetronomeApplication
import org.openmetronome.R
import org.openmetronome.audio.MetronomeState
import org.openmetronome.data.*
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.Instant

/** Optional launcher work must never terminate the playback process. */
internal val widgetFailureHandler = CoroutineExceptionHandler { _, error ->
    Log.w("OpenMetronomeWidget", "Widget refresh failed; playback is unaffected", error)
}

/** A process-local projection, never a persisted claim that playback survived process death. */
object WidgetHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + widgetFailureHandler)
    private val playback = MutableStateFlow<WidgetPlayback?>(null)
    private var controlsJob: Job? = null
    private var dateJob: Job? = null
    private var statisticsJob: Job? = null
    private val clockRevision = MutableStateFlow(0L)
    private val statisticsRetry = MutableStateFlow(0L)
    @Volatile private var statistics = "Ładowanie statystyk…"
    @Volatile private var metronomeStatistics: Result<WidgetStatistics>? = null
    @Volatile private var musicStatistics: Result<WidgetStatistics>? = null
    private val providers = listOf(MiniWidget::class.java, ControlWidget::class.java, StatisticsWidget::class.java,
        AddActivityWidget::class.java, ActivityTemplateWidget::class.java, ActivityShortcutsWidget::class.java)

    private fun ids(context: Context, provider: Class<*>) = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, provider))
    fun hasWidgets(context: Context) = providers.any { ids(context, it).isNotEmpty() }

    fun publish(context: Context, state: MetronomeState) = publishSnapshot(context, WidgetModel.playback(state))
    fun publishSnapshot(context: Context, snapshot: WidgetPlayback) {
        playback.value = snapshot
        try {
            if (hasWidgets(context)) observe(context.applicationContext)
        } catch (error: RuntimeException) {
            Log.w("OpenMetronomeWidget", "Launcher unavailable", error)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe(context: Context) {
        if (!hasWidgets(context)) {
            scope.coroutineContext.cancelChildren()
            controlsJob = null
            dateJob = null
            statisticsJob = null
            return
        }
        if (controlsJob?.isActive != true) {
            controlsJob = scope.launch { playback.filterNotNull().collect { drawControls(context, it) } }
        }
        if (ids(context, StatisticsWidget::class.java).isEmpty()) {
            statisticsJob?.cancel(); statisticsJob = null
            dateJob?.cancel(); dateJob = null
            return
        }
        val data = (context as OpenMetronomeApplication).data
        if (dateJob?.isActive != true) {
            dateJob = scope.launch {
                combine(data.settings.musicStatistics, clockRevision) { settings, _ -> settings?.zone ?: java.time.ZoneId.systemDefault() }.collectLatest { zone ->
                    while (hasWidgets(context)) {
                        val deviceMidnight = millisUntilNextPracticeDate(ZonedDateTime.now())
                        val musicMidnight = millisUntilNextPracticeDate(ZonedDateTime.now(zone))
                        delay(minOf(deviceMidnight, musicMidnight))
                        refresh(context)
                    }
                }
            }
        }
        if (statisticsJob?.isActive == true) return
        statisticsJob = scope.launch(Dispatchers.IO) {
            // Room changes include final writes and restored history; no polling of the audio engine.
            val activityChanges = statisticsRetry.flatMapLatest {
                data.musicActivities.map { true }.catch { error ->
                    if (error is CancellationException) throw error else emit(false)
                }
            }
            val historyChanges = statisticsRetry.flatMapLatest { data.statisticsHistory() }
            val preferenceChanges = statisticsRetry.flatMapLatest { data.statisticsPreferences() }
            val musicSettings = statisticsRetry.flatMapLatest { data.settings.musicStatistics.catch { error ->
                if (error is CancellationException) throw error else emit(null)
            } }
            combine(historyChanges, preferenceChanges, activityChanges,
                musicSettings) { history, preferences, musicReadable, _ -> Triple(history, preferences, musicReadable) }
                .conflate().collectLatest { (history, preferences, musicReadable) ->
                    try {
                        if (history is StatisticsHistory.Ready && preferences != null) {
                            updateStatistics(context, data, history, preferences.getOrThrow(), musicReadable)
                        } else {
                            statistics = if (history is StatisticsHistory.Unavailable) "Statystyki niedostępne" else "Ładowanie statystyk…"
                            if (history is StatisticsHistory.Unavailable || preferences?.isFailure == true) {
                                metronomeStatistics = Result.failure(IllegalStateException(statistics))
                                musicStatistics = Result.failure(IllegalStateException(statistics))
                            } else { metronomeStatistics = null; musicStatistics = null }
                            drawStatistics(context)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { statistics = "Statystyki niedostępne"; metronomeStatistics = Result.failure(error); musicStatistics = Result.failure(error); drawStatistics(context) }
                }
        }

    }

    fun widgetsChanged(context: Context) {
        try { observe(context.applicationContext) }
        catch (error: RuntimeException) { Log.w("OpenMetronomeWidget", "Launcher unavailable", error) }
    }

    fun clockChanged() { clockRevision.value += 1 }

    suspend fun refresh(context: Context) {
        val app = context.applicationContext as OpenMetronomeApplication
        if (!hasWidgets(app)) return
        statisticsRetry.value += 1
        observe(app)
        drawActivityWidgets(app)
        withContext(Dispatchers.IO) {
            try {
                app.data.ready.await()
                val saved = app.data.settings.load()
                withContext(Dispatchers.Main.immediate) {
                    drawControls(app, playback.value ?: WidgetModel.playback(MetronomeState(config = saved)))
                }
                if (ids(app, StatisticsWidget::class.java).isNotEmpty()) drawStatistics(app)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                statistics = "Dane niedostępne · otwórz aplikację"
                metronomeStatistics = Result.failure(IllegalStateException(statistics)); musicStatistics = Result.failure(IllegalStateException(statistics))
                drawStatistics(app)
                withContext(Dispatchers.Main.immediate) { drawControls(app, playback.value, statistics) }
            }
        }
    }

    private suspend fun updateStatistics(context: Context, data: AppData, history: StatisticsHistory.Ready, preferences: StatisticsPreferences, musicReadable: Boolean = true) {
        if (ids(context, StatisticsWidget::class.java).isEmpty()) return
        val today = LocalDate.now()
        metronomeStatistics = try {
            val daily = data.dailyPractice(today).first { it !is DailyPractice.Loading }
            check(daily is DailyPractice.Ready) { "Practice statistics unavailable" }
            // Short sessions still contribute to today's saved total.
            val analytics = PracticeAnalyticsCalculator.calculate(history.sessions, preferences, today) { null }
            Result.success(WidgetStatisticsProjector.metronome(today, daily.durationMs, analytics))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
        musicStatistics = if (!musicReadable) Result.failure(IllegalStateException("Music activities unavailable")) else try {
            when (val value = data.observeMusicStatistics(flowOf(Instant.now())).first { it !is MusicStatisticsState.Loading }) {
                is MusicStatisticsState.Ready -> Result.success(WidgetStatisticsProjector.music(value.summary))
                else -> Result.failure(IllegalStateException("Music statistics unavailable"))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
        statistics = metronomeStatistics?.getOrNull()?.let { dailyPracticeDuration(it.todayMs) } ?: "Statystyki niedostępne"
        drawStatistics(context)
    }

    private fun open(context: Context, statistics: Boolean = false, id: Int = 0, music: Boolean = false): PendingIntent = PendingIntent.getActivity(context,
        if (statistics) 1000 + id else 1, Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("widget_statistics", statistics)
            if (statistics) putExtra("widget_music_scope", music)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    internal fun statisticsIntent(context: Context, id: Int, music: Boolean): PendingIntent = open(context, true, id, music)

    private fun action(context: Context, command: String): PendingIntent = PendingIntent.getBroadcast(context,
        WidgetModel.commands.indexOf(command) + 10, Intent(context, WidgetCommandReceiver::class.java).setAction(command),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun activityIntent(context: Context, id: Int, slot: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction("org.openmetronome.widget.ACTIVITY.$id.$slot")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("widget_activity_id", id).putExtra("widget_activity_slot", slot)
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun configureIntent(context: Context, id: Int): PendingIntent = PendingIntent.getActivity(context, id,
        Intent(context, WidgetConfigureActivity::class.java).setAction("org.openmetronome.widget.CONFIGURE.$id")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun drawActivityWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val store = WidgetConfigurationStore(context)
        for (id in ids(context, AddActivityWidget::class.java)) {
            manager.updateAppWidget(id, activityViews(context, id, AddActivityWidget::class.java, store.get(id)))
        }
        for (id in ids(context, ActivityTemplateWidget::class.java)) {
            manager.updateAppWidget(id, activityViews(context, id, ActivityTemplateWidget::class.java, store.get(id)))
        }
        for (id in ids(context, ActivityShortcutsWidget::class.java)) {
            manager.updateAppWidget(id, activityViews(context, id, ActivityShortcutsWidget::class.java, store.get(id)))
        }
    }

    internal fun activityViews(context: Context, id: Int, provider: Class<*>, config: WidgetConfiguration): RemoteViews {
        if (provider == AddActivityWidget::class.java) {
            return RemoteViews(context.packageName, R.layout.widget_activity_add).apply {
                setOnClickPendingIntent(R.id.widget_activity_add, activityIntent(context, id, -1))
            }
        }
        if (provider == ActivityTemplateWidget::class.java) {
            return RemoteViews(context.packageName, R.layout.widget_activity_template).apply {
                setTextViewText(R.id.widget_activity_open, "+ ${config.category.widgetLabel()}\n${config.instrument.takeIf { it.isNotBlank() }?.let { "$it\n" }.orEmpty()}${config.durationMinutes} min")
                setOnClickPendingIntent(R.id.widget_activity_open, activityIntent(context, id, -1))
                setOnClickPendingIntent(R.id.widget_activity_configure, configureIntent(context, id))
            }
        }
        require(provider == ActivityShortcutsWidget::class.java)
        return RemoteViews(context.packageName, R.layout.widget_activity_shortcuts).apply {
            listOf(R.id.widget_shortcut_1, R.id.widget_shortcut_2, R.id.widget_shortcut_3, R.id.widget_shortcut_4).forEachIndexed { index, view ->
                val shortcut = config.shortcuts.getOrNull(index) ?: defaultWidgetShortcuts()[index]
                val duration = if (shortcut.durationMinutes % 60 == 0) "${shortcut.durationMinutes / 60}h" else "${shortcut.durationMinutes}m"
                setTextViewText(view, "${shortcut.category.compactWidgetLabel()}\n$duration")
                val instrument = shortcut.instrument.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
                setContentDescription(view, "Dodaj: ${shortcut.category.widgetLabel()}$instrument, ${shortcut.durationMinutes} minut")
                setOnClickPendingIntent(view, activityIntent(context, id, index))
            }
            setOnClickPendingIntent(R.id.widget_activity_configure, configureIntent(context, id))
        }
    }

    private fun drawControls(context: Context, value: WidgetPlayback?, error: String? = null) {
        val manager = AppWidgetManager.getInstance(context)
        for (provider in listOf(MiniWidget::class.java, ControlWidget::class.java)) for (id in ids(context, provider)) {
            val controls = provider == ControlWidget::class.java
            val expanded = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) >= 220
            val views = controlViews(context, value, controls, expanded, error)
            manager.updateAppWidget(id, views)
        }
    }

    internal fun controlViews(context: Context, value: WidgetPlayback?, controls: Boolean, expanded: Boolean,
        error: String? = null): RemoteViews {
        val views = RemoteViews(context.packageName, if (controls) R.layout.widget_control else R.layout.widget_mini)
        views.setTextViewText(R.id.widget_bpm, value?.let { "${it.bpm} BPM" } ?: "— BPM")
        views.setOnClickPendingIntent(R.id.widget_bpm, open(context))
        val status = error ?: value?.status ?: "Otwórz aplikację"
        val needsStatus = error != null || value == null || value.status !in setOf("Gra", "Zatrzymany")
        views.setTextViewText(R.id.widget_status, status)
        views.setViewVisibility(R.id.widget_status, if (needsStatus) View.VISIBLE else View.GONE)
        views.setContentDescription(R.id.widget_bpm, value?.let { "${it.bpm} BPM, ${it.status}" } ?: status)
        views.setTextViewText(R.id.widget_play, if (value?.running == true) "■ Stop" else "▶ Start")
        views.setOnClickPendingIntent(R.id.widget_play, if (value == null) open(context) else action(context, if (value.running) "stop" else "start"))
        if (controls) {
            views.setTextViewText(R.id.widget_base, value?.let { "${it.base} × ${it.percent}%${if (it.pending) " →" else ""}" } ?: "")
            views.setViewVisibility(R.id.widget_base, if (value != null && (value.percent != 100 || value.pending)) View.VISIBLE else View.GONE)
            views.setContentDescription(R.id.widget_base, value?.let {
                "Baza ${it.base} BPM, ${it.percent}%${if (it.pending) ", zmiana oczekuje na zastosowanie" else ""}"
            } ?: "")
            listOf(R.id.widget_100 to 100, R.id.widget_75 to 75, R.id.widget_50 to 50, R.id.widget_25 to 25).forEach { (view, percent) ->
                views.setTextViewText(view, "$percent%")
                views.setContentDescription(view, "$percent%${if (value?.percent == percent) ", wybrane" else ""}")
                views.setInt(view, "setBackgroundResource", if (value?.percent == percent) R.drawable.widget_selected else R.drawable.widget_outline)
                views.setTextColor(view, if (value?.percent == percent) 0xFF102B1B.toInt() else 0xFFE0E2DB.toInt())
                views.setOnClickPendingIntent(view, action(context, percent.toString()))
            }
            views.setViewVisibility(R.id.widget_steps, if (expanded) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.widget_minus, action(context, "minus"))
            views.setOnClickPendingIntent(R.id.widget_plus, action(context, "plus"))
        }
        return views
    }

    private fun drawStatistics(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val configStore = WidgetConfigurationStore(context)
        for (id in ids(context, StatisticsWidget::class.java)) {
            val config = configStore.get(id)
            val value = if (config.scope == WidgetStatisticsScope.MUSIC) musicStatistics else metronomeStatistics
            val expanded = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) >= 190
            manager.updateAppWidget(id, statisticsViews(context, id, config, value, expanded))
        }
    }

    internal fun statisticsViews(context: Context, id: Int, config: WidgetConfiguration,
        value: Result<WidgetStatistics>?, expanded: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_statistics)
            views.setTextViewText(R.id.widget_statistics_title, if (config.scope == WidgetStatisticsScope.MUSIC) "Dzisiaj · cała aktywność" else "Dzisiaj · metronom")
            val summary = value?.getOrNull()
            views.setTextViewText(R.id.widget_stats, when { value == null -> "Ładowanie statystyk…"; value.isFailure -> "Statystyki niedostępne"; else -> dailyPracticeDuration(summary!!.todayMs) })
            views.setTextViewText(R.id.widget_streak, summary?.let {
                listOfNotNull(if (!it.hasData) "Brak danych" else null,
                    if (config.showStreak) "Seria: ${it.streakDays} dni" else null,
                    if (config.showWeek) "Tydzień: ${dailyPracticeDuration(it.weekMs)}" else null,
                    if (config.showActiveDays) it.weeklyGoal?.let { goal -> "Cel: ${it.activeDays}/$goal dni" } ?: "Aktywne dni: ${it.activeDays}" else null).joinToString(" · ")
            }.orEmpty())
            views.setTextViewText(R.id.widget_week, if (expanded && config.showChart && summary != null) {
                val maximum = summary.week.maxOf { it.durationMs }
                val bars = "▁▂▃▄▅▆▇█"
                listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd").zip(summary.week)
                    .joinToString("  ") { (name, day) ->
                        val bar = if (day.durationMs == 0L || maximum == 0L) "·" else bars[(day.durationMs.toDouble() / maximum * 7).toInt().coerceIn(0, 7)].toString()
                        "$name $bar"
                    }
            } else "")
            views.setContentDescription(R.id.widget_week, summary?.week?.zip(listOf("poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela"))
                ?.joinToString(", ") { (day, name) -> "$name: ${dailyPracticeDuration(day.durationMs)}" }.orEmpty())
            views.setOnClickPendingIntent(R.id.widget_root, open(context, true, id, config.scope == WidgetStatisticsScope.MUSIC))
            views.setOnClickPendingIntent(R.id.widget_configure, configureIntent(context, id))
            return views
    }
}
