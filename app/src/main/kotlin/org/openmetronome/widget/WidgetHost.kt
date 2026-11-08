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
    @Volatile private var statistics = "Ładowanie statystyk…"
    private val providers = listOf(MiniWidget::class.java, ControlWidget::class.java, StatisticsWidget::class.java)

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
        if (dateJob?.isActive != true) {
            dateJob = scope.launch {
                while (hasWidgets(context)) {
                    delay(millisUntilNextPracticeDate(ZonedDateTime.now()))
                    refresh(context)
                }
            }
        }
        if (ids(context, StatisticsWidget::class.java).isEmpty()) {
            statisticsJob?.cancel(); statisticsJob = null
            return
        }
        if (statisticsJob?.isActive == true) return
        val data = (context as OpenMetronomeApplication).data
        statisticsJob = scope.launch(Dispatchers.IO) {
            // Room changes include final writes and restored history; no polling of the audio engine.
            combine(data.statisticsHistory(), data.statisticsPreferences()) { history, preferences -> history to preferences }
                .conflate().collect { (history, preferences) ->
                    try {
                        if (history is StatisticsHistory.Ready && preferences != null) {
                            updateStatistics(context, data, history, preferences.getOrThrow())
                        } else {
                            statistics = if (history is StatisticsHistory.Unavailable) "Statystyki niedostępne" else "Ładowanie statystyk…"
                            drawStatistics(context)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { statistics = "Statystyki niedostępne"; drawStatistics(context) }
                }
        }

    }

    fun widgetsChanged(context: Context) {
        try { observe(context.applicationContext) }
        catch (error: RuntimeException) { Log.w("OpenMetronomeWidget", "Launcher unavailable", error) }
    }

    suspend fun refresh(context: Context) {
        val app = context.applicationContext as OpenMetronomeApplication
        if (!hasWidgets(app)) return
        observe(app)
        withContext(Dispatchers.IO) {
            try {
                app.data.ready.await()
                val saved = app.data.settings.load()
                withContext(Dispatchers.Main.immediate) {
                    drawControls(app, playback.value ?: WidgetModel.playback(MetronomeState(config = saved)))
                }
                if (ids(app, StatisticsWidget::class.java).isEmpty()) return@withContext
                val history = app.data.statisticsHistory().first { it !is StatisticsHistory.Loading }
                val preferences = app.data.statisticsPreferences().filterNotNull().first().getOrThrow()
                if (history is StatisticsHistory.Ready) updateStatistics(app, app.data, history, preferences)
                else { statistics = "Statystyki niedostępne"; drawStatistics(app) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                statistics = "Dane niedostępne · otwórz aplikację"
                drawStatistics(app)
                withContext(Dispatchers.Main.immediate) { drawControls(app, playback.value, statistics) }
            }
        }
    }

    private suspend fun updateStatistics(context: Context, data: AppData, history: StatisticsHistory.Ready, preferences: StatisticsPreferences) {
        if (ids(context, StatisticsWidget::class.java).isEmpty()) return
        val today = LocalDate.now()
        val daily = data.dailyPractice(today).first { it !is DailyPractice.Loading }
        if (daily !is DailyPractice.Ready) { statistics = "Statystyki niedostępne"; drawStatistics(context); return }
        // Summary needs no detailed timelines. Short sessions still contribute to the large daily total.
        val analytics = PracticeAnalyticsCalculator.calculate(history.sessions, preferences, today) { null }
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val names = listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd")
        val week = names.mapIndexed { index, name -> "$name ${if (monday.plusDays(index.toLong()) in analytics.activeDates) "●" else "○"}" }.joinToString("  ")
        statistics = "${dailyPracticeDuration(daily.durationMs)}\nSeria: ${analytics.summary.currentStreak} dni\n$week"
        drawStatistics(context)
    }

    private fun open(context: Context, statistics: Boolean = false): PendingIntent = PendingIntent.getActivity(context,
        if (statistics) 2 else 1, Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("widget_statistics", statistics)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(context: Context, command: String): PendingIntent = PendingIntent.getBroadcast(context,
        WidgetModel.commands.indexOf(command) + 10, Intent(context, WidgetCommandReceiver::class.java).setAction(command),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

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
        views.setOnClickPendingIntent(R.id.widget_title, open(context))
        views.setViewVisibility(R.id.widget_title, if (context.resources.configuration.fontScale > 1.2f) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.widget_bpm, value?.let { "${it.bpm} BPM" } ?: "— BPM")
        views.setOnClickPendingIntent(R.id.widget_bpm, open(context))
        views.setTextViewText(R.id.widget_status, error ?: value?.status ?: "Otwórz aplikację")
        views.setTextViewText(R.id.widget_play, if (value?.running == true) "■ Stop" else "▶ Start")
        views.setOnClickPendingIntent(R.id.widget_play, if (value == null) open(context) else action(context, if (value.running) "stop" else "start"))
        if (controls) {
            views.setTextViewText(R.id.widget_base, value?.let { "Baza ${it.base} × ${it.percent}%${if (it.pending) " · oczekuje" else ""}" } ?: "")
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
        for (id in ids(context, StatisticsWidget::class.java)) {
            val views = RemoteViews(context.packageName, R.layout.widget_statistics)
            val lines = statistics.lines()
            views.setTextViewText(R.id.widget_stats, lines.first())
            views.setTextViewText(R.id.widget_streak, lines.getOrElse(1) { "" })
            views.setTextViewText(R.id.widget_week, lines.getOrElse(2) { "" })
            views.setOnClickPendingIntent(R.id.widget_root, open(context, true))
            manager.updateAppWidget(id, views)
        }
    }
}
