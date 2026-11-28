package org.openmetronome.data

import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openmetronome.data.BackupArchive.writeString
import org.openmetronome.MainActivity
import org.openmetronome.TestApplication
import org.openmetronome.UnlockedScreenRule
import org.openmetronome.domain.audio.SamplePcm
import org.openmetronome.domain.model.*

/** Deliberately excluded from regular emulator CI; run on disposable emulators. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class BackupBaselineQualification

@RunWith(AndroidJUnit4::class)
class BackupBaselineTest {
    @get:Rule(order = 0) val screen = UnlockedScreenRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()

    @Test
    @BackupBaselineQualification
    fun capture(): Unit = runBlocking {
        assumeTrue("Explicit benchmark only", args.getString("backupBaseline") == "true")
        check(args.getString("allowAudio") == "false" && args.getString("allowHaptics") == "false")
        check(Build.FINGERPRINT.contains("generic") || Build.HARDWARE in setOf("ranchu", "goldfish"))
        val count = requireNotNull(args.getString("count")).toInt().also { require(it in setOf(10_000, 50_000, 100_000)) }
        val profile = requireNotNull(args.getString("profile")).also { require(it in setOf("simple", "dense")) }
        val context = instrumentation.targetContext
        val app = context.applicationContext as TestApplication
        val dir = File(context.cacheDir, "backup-baseline-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val reportPath = File(context.cacheDir, "backup-baseline-$count-$profile.json")
        val results = JSONArray()
        val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("playbacks", count)
            .put("profile", profile).put("maxHeapBytes", Runtime.getRuntime().maxMemory()).put("phases", results)
        fun saveReport() = reportPath.writeText(report.toString(2))
        fun add(value: JSONObject) { results.put(value); saveReport() }
        report.put("status", "running")
        saveReport()
        val data = AppData(context, dir)
        try {
            val config = MetronomeConfig(tempo = Tempo(123), sound = SoundConfig(customSampleId = id(3, 0)))
            val archive = File(dir, "export.ombak")
            val fallback = File(dir, "small-incoming.ombak")
            data.ready.await()
            val sampleHashes = linkedMapOf<String, String>()
            measure(dir, "fixture", ::add) { _, result ->
                seed(data, dir, count, profile == "dense", config, sampleHashes)
                result.put("rows", rowCounts(data))
                fallback.outputStream().use {
                    BackupArchive.write(BackupSnapshot(
                        ConfigCodec.encode(MetronomeConfig(tempo = Tempo(77))), emptyList(), emptyList(),
                        emptyList(), emptyList(), emptyMap()), it)
                }
            }
            for (visible in listOf(false, true)) {
                val mode = if (visible) "statistics" else "closed"
                var activity: ActivityScenario<MainActivity>? = null
                try {
                    if (visible) {
                        app.backupBaselineData = data
                        measure(dir, "open-statistics", ::add) { _, _ ->
                            activity = ActivityScenario.launch<MainActivity>(
                                Intent(context, MainActivity::class.java).putExtra("widget_statistics", true)
                                    .putExtra("widget_music_scope", true))
                            compose.waitUntil(90_000) {
                                compose.onAllNodes(hasText("Cała aktywność") and isSelected())
                                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() &&
                                    compose.onAllNodes(hasText("Dzisiaj"))
                                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
                            }
                        }
                    }
                    measure(dir, "$mode-preflight", ::add) { _, result -> result.put("estimate", preflight(data)) }
                    if (archive.exists()) check(archive.delete())
                    measure(dir, "$mode-export", ::add) { gauge, result ->
                        val start = now()
                        var opened = false
                        val phases = JSONObject()
                        result.put("stageStartsMs", phases)
                        data.backupExportPhase = { phase ->
                            phases.put(phase.name, ms(now() - start))
                            gauge.capture()
                        }
                        try {
                            data.exportBackup {
                            opened = true
                            result.put("prepareThroughValidationMs", ms(now() - start))
                            result.put("startBlockedAtDestination", playbackBlocked(data))
                            object : FilterOutputStream(archive.outputStream()) {
                                override fun write(bytes: ByteArray, offset: Int, count: Int) =
                                    out.write(bytes, offset, count)
                                override fun close() {
                                    super.close()
                                    result.put("destinationClosedMs", ms(now() - start))
                                    gauge.capture()
                                }
                            }
                        }
                            awaitRelease(data, start, result)
                        } finally { data.backupExportPhase = {} }
                        if (phases.has("VALIDATION")) {
                            result.put("reservationMs", phases.getDouble("VALIDATION"))
                        }
                        result.put("openedDestination", opened)
                        result.put("outcome", if (opened && data.backupNotice.value == "Kopia zapasowa została zapisana.") "saved"
                            else if (!opened && isOverBudget(data)) "limit-refusal" else "unexpected-failure")
                        result.put("notice", data.backupNotice.value)
                        assertNotEquals("unexpected-failure", result.getString("outcome"))
                        if (opened) assertFalse(result.getBoolean("startBlockedAtDestination"))
                    }
                    if (archive.isFile) {
                        measure(dir, "$mode-validate-archive", ::add) { _, result ->
                            val decoded = archive.inputStream().use(BackupArchive::read)
                            assertEquals(count, decoded.sessions.size)
                            assertEquals(count / 5, decoded.musicActivities.size)
                            assertEquals(sampleHashes, decoded.sampleFiles.mapValues { digest(it.value) })
                            result.put("outcome", "validated")
                        }
                    } else {
                        add(JSONObject().put("phase", "$mode-validate-archive").put("outcome", "no-archive-after-limit"))
                    }
                    val full = archive.isFile
                    val source = if (full) archive else fallback
                    val expectedRows = if (full) fingerprint(data) else null
                    data.settings.save(config.copy(tempo = Tempo(79)))
                    measure(dir, "$mode-restore", ::add) { gauge, result ->
                        result.put("source", if (full) "same-history" else "small-valid-archive")
                        restore(data, dir, source, BackupStreamLimits(), gauge, result)
                    }
                    if (full) {
                        assertEquals(expectedRows, fingerprint(data))
                        assertEquals(config, data.settings.load())
                        assertEquals(sampleHashes, sampleHashes.keys.associateWith { digest(File(dir, "samples/$it.wav").readBytes()) })
                    } else {
                        assertEquals(0L, data.dao.total().first().sessions)
                        assertEquals(77, data.settings.load().tempo.bpm)
                        assertTrue(data.samples.items.value.isEmpty())
                        activity?.close()
                        activity = null
                        app.backupBaselineData = null
                        measure(dir, "$mode-reseed", ::add) { _, _ ->
                            seed(data, dir, count, profile == "dense", config, sampleHashes)
                        }
                    }
                } finally {
                    activity?.close()
                    app.backupBaselineData = null
                }
            }
            if (args.getString("backupExperiment") == "true") {
                // Test-only envelope. The application still applies production limits.
                val limits = BackupStreamLimits(expanded = 256L * 1024 * 1024,
                    items = 150_000, activities = 30_000, intervals = 120_000, corrections = 100_000)
                val owner = currentCoroutineContext()
                BackupStaging.create(File(dir, "backup-staging")).use { stage ->
                    measure(dir, "experiment-export", ::add) { gauge, result ->
                        val start = now()
                        val phases = JSONObject()
                        result.put("stageStartsMs", phases).put("expandedLimit", limits.expanded)
                            .put("itemLimit", limits.items).put("activityLimit", limits.activities)
                            .put("intervalLimit", limits.intervals)
                        data.samples.backup {
                            data.database.withTransaction {
                                StreamingBackupWriter(data.database.openHelper.writableDatabase, limits, owner::ensureActive)
                                    .write(stage.archive, 3, ConfigCodec.encode(data.settings.load()),
                                        data.settings.preferencesSnapshot(), ::backupSample) {
                                        phases.put(it.name, ms(now() - start))
                                        gauge.capture()
                                    }
                            }
                        }
                        result.put("archiveBytes", stage.archive.length())
                        phases.put("VALIDATION", ms(now() - start))
                        StreamingBackupValidator(limits, owner::ensureActive).validate(stage.archive, stage.index)
                        result.put("outcome", "validated")
                    }
                    val expectedRows = fingerprint(data)
                    val expectedPreferences = data.settings.preferencesSnapshot()
                    data.settings.save(config.copy(tempo = Tempo(79)))
                    measure(dir, "experiment-restore", ::add) { gauge, result ->
                        restore(data, dir, stage.archive, limits, gauge, result)
                    }
                    assertEquals(expectedRows, fingerprint(data))
                    assertEquals(expectedPreferences, data.settings.preferencesSnapshot())
                    assertEquals(config, data.settings.load())
                    assertEquals(sampleHashes, sampleHashes.keys.associateWith { digest(File(dir, "samples/$it.wav").readBytes()) })
                }
            }
            val next = playback(count, ConfigCodec.encode(config), emptyList())
            assertTrue(data.reservePlayback())
            try { data.recordSession(next) } finally { data.playbackFinished() }
            assertEquals(count + 1L, data.dao.total().first().sessions)
            assertFalse(data.sessionWriteFailed.value)
            report.put("postBackupSessionPersisted", true).put("status", "complete")
        } finally {
            saveReport()
            data.closeTestStorage()
            dir.deleteRecursively()
        }
    }

    private suspend fun awaitRelease(data: AppData, start: Long, result: JSONObject) {
        data.backupWorking.first { !it }
        result.put("reservationMs", ms(now() - start))
        data.awaitWrites()
        assertTrue(data.reservePlayback())
        data.playbackFinished()
    }

    private suspend fun restore(data: AppData, dir: File, source: File, limits: BackupStreamLimits,
        gauge: Gauge, result: JSONObject) {
        val checkpoints = JSONObject()
        result.put("sourceBytes", source.length()).put("checkpointsMs", checkpoints)
        val start = now()
        data.restoreCheckpoint = { stage ->
            checkpoints.put(stage.name, ms(now() - start))
            if (stage == RestoreCheckpoint.PREPARED) {
                result.put("startBlockedAtReady", playbackBlocked(data))
                var editCalled = false
                data.editConfiguration { editCalled = true }
                result.put("configurationEditBlockedAtReady", !editCalled)
                val journal = File(dir, "restore-journal/active")
                result.put("beforeArchiveBytes", RestoreDiskSnapshot(File(journal, "before")).sizeOnDisk())
                result.put("afterArchiveBytes", RestoreDiskSnapshot(File(journal, "after")).sizeOnDisk())
                result.put("rollbackReserveBytes", File(journal, "rollback-reserve").length())
            }
            gauge.capture()
        }
        try {
            data.importBackup({
                object : FilterInputStream(source.inputStream()) {
                    override fun close() {
                        super.close()
                        result.put("sourceReadMs", ms(now() - start))
                    }
                }
            }, {}, limits)
            awaitRelease(data, start, result)
        } finally { data.restoreCheckpoint = {} }
        val notice = data.backupNotice.value.orEmpty()
        result.put("notice", notice)
        assertTrue(notice, notice.startsWith("Kopia została przywrócona:"))
        assertTrue(result.getBoolean("startBlockedAtReady"))
        assertTrue(result.getBoolean("configurationEditBlockedAtReady"))
        assertTrue(result.getLong("beforeArchiveBytes") > 0)
        assertTrue(result.getLong("afterArchiveBytes") > 0)
        assertFalse(data.recoveryRequired.value)
        result.put("outcome", "restored")
    }

    private fun fingerprint(data: AppData): String {
        val queries = mapOf("practice_sessions" to "id", "practice_days" to "sessionId,date",
            "presets" to "id", "custom_samples" to "id", "music_activities" to "id",
            "music_activity_intervals" to "activityId,startAt", "grouping_corrections" to "id")
        val digest = MessageDigest.getInstance("SHA-256")
        val discard = object : java.io.OutputStream() { override fun write(value: Int) = Unit }
        val out = java.io.DataOutputStream(java.security.DigestOutputStream(discard, digest))
        for ((table, key) in queries) {
            out.writeString(table)
            data.database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY $key").use { cursor ->
                while (cursor.moveToNext()) {
                    for (column in 0 until cursor.columnCount) {
                        val type = cursor.getType(column)
                        out.writeByte(type)
                        when (type) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> out.writeLong(cursor.getLong(column))
                            android.database.Cursor.FIELD_TYPE_STRING -> out.writeString(cursor.getString(column))
                            android.database.Cursor.FIELD_TYPE_NULL -> Unit
                            else -> error("Unexpected fixture column type")
                        }
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun playbackBlocked(data: AppData): Boolean {
        if (!data.reservePlayback()) return true
        data.playbackFinished()
        return false
    }

    private suspend fun preflight(data: AppData): JSONObject {
        val history = data.dao.backupSessionBytes()
        val activities = data.database.musicActivities().backupSize()
        val grouping = data.database.grouping().backupSize()
        val combined = history + activities.activityBytes + 16 * activities.intervals + grouping.bytes
        return JSONObject().put("historyBytes", history).put("estimatedCombinedBytes", combined)
            .put("activities", activities.activities).put("intervals", activities.intervals).put("corrections", grouping.records)
            .put("firstRefusedBy", when {
                history > BackupArchive.MAX_ARCHIVE_BYTES -> "history-bytes"
                activities.activities > BackupArchive.MAX_MUSIC_ACTIVITIES -> "activity-count"
                activities.intervals > BackupArchive.MAX_MUSIC_INTERVALS -> "interval-count"
                grouping.records > BackupArchive.MAX_GROUPING_CORRECTIONS -> "correction-count"
                combined > BackupArchive.MAX_ARCHIVE_BYTES -> "combined-bytes"
                else -> "none"
            })
    }

    private suspend fun isOverBudget(data: AppData) = preflight(data).getString("firstRefusedBy") != "none"

    private fun rowCounts(data: AppData): JSONObject {
        val tables = listOf("practice_sessions", "practice_days", "presets", "custom_samples", "music_activities",
            "music_activity_intervals", "grouping_corrections")
        return JSONObject().apply {
            for (table in tables) data.database.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use {
                check(it.moveToFirst())
                put(table, it.getLong(0))
            }
            for (table in listOf("music_activities", "grouping_corrections")) {
                data.database.openHelper.readableDatabase.query("SELECT count(*) FROM $table WHERE deletedAt IS NOT NULL").use {
                    check(it.moveToFirst())
                    put("${table}_deleted", it.getLong(0))
                }
            }
        }
    }

    private suspend fun seed(data: AppData, dir: File, count: Int, dense: Boolean, config: MetronomeConfig,
        hashes: MutableMap<String, String>) {
        val liveConfig = ConfigCodec.encode(config)
        val alternate = List(4) { ConfigCodec.encode(config.copy(tempo = Tempo(110 + it))) }
        fun timeline(changes: Int) = SessionTimelineCodec.encode(SessionTimeline(true,
            List(changes) { SessionConfiguration(it * 1_000L, alternate[it % alternate.size]) }))
        val short = timeline(1)
        val long = if (dense) timeline(20) else short
        val outlier = if (dense) timeline(200) else short
        for (start in 0 until count step 256) {
            val sessions = (start until minOf(count, start + 256)).map { i ->
                playback(i, liveConfig, listOf(short, long, outlier), dense)
            }
            val days = sessions.flatMap { PracticeTimeline.days(it.id, it.startedAt, it.durationMs, ZoneId.of(it.zoneId)) }
            data.database.withTransaction {
                data.dao.insertSessions(sessions)
                data.dao.insertPracticeDays(days)
            }
        }
        val activityDao = data.database.musicActivities()
        for (start in 0 until count / 5 step 256) data.database.withTransaction {
            for (i in start until minOf(count / 5, start + 256)) {
                val anchor = START + i * 2L * 60 * 60 * 1_000
                val fit = i % 2 == 0
                val intervals = List(if (fit) 7 else 1) { index ->
                    MusicActivityInterval(anchor + index * 120_000L, anchor + index * 120_000L + 60_000)
                }
                val last = intervals.last().endAt
                val details = MusicActivityDetails(MusicActivityKind.INDIVIDUAL_PRACTICE,
                    if (fit) MusicActivitySource.FIT else MusicActivitySource.MANUAL, anchor, last, "UTC",
                    intervals.size * 60_000L, if (fit) 1_000L else 1L, intervals,
                    instrument = if (i % 5 == 0) "Test instrument" else null,
                    note = if (i % 3 == 0) "Syntetyczna notatka ♪ ".repeat(if (dense) 10 else 4).trim() else null)
                activityDao.insert(MusicActivity(id(2, i), details, START, START + 1, if (i % 10 == 0) START + 1 else null))
            }
        }
        data.database.grouping().insert(List(count / 100) { i ->
            GroupingCorrectionRow(GroupingCorrection(id(4, i), i + 1L, GroupingCorrectionKind.FINISH_AFTER,
                id(1, i * 100), deletedAt = if (i % 10 == 0) START + 1 else null))
        })
        val samples = List(32) { i ->
            val sampleId = id(3, i)
            val wav = SamplePcm.encodeCanonical(ShortArray(SamplePcm.MAX_FRAMES).apply {
                this[60] = (i + 1).toShort()
                this[61] = (-i - 1).toShort()
            })
            File(dir, "samples/$sampleId.wav").writeBytes(wav)
            hashes[sampleId] = digest(wav)
            CustomSampleRecord(sampleId, "Synthetic $i", START + i)
        }
        data.dao.insertSamples(samples)
        data.dao.insertPresets(List(32) { i -> Preset(id(5, i), "Preset $i", alternate[i % 4], START, START) })
        data.samples.load()
        assertEquals(32, data.samples.items.value.count { it.available })
        data.settings.save(config)
        data.database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use {
            check(it.moveToFirst() && it.getInt(0) == 0)
        }
    }

    private fun playback(index: Int, config: String, timelines: List<String>, dense: Boolean = false): PracticeSession {
        val acrossMidnight = index % 48 == 47
        val dst = DST_SESSIONS.getOrNull(index)
        val anchor = dst ?: (START + index * 1_800_000L + if (acrossMidnight) 1_500_000 else 0)
        val length = if (acrossMidnight || dst != null) 900_000L else 300_000L
        val timeline = when {
            timelines.isEmpty() -> null
            dense && index % 1000 == 0 -> timelines[2]
            dense && index % 12 == 0 -> timelines[1]
            index % 4 == 0 -> timelines[0]
            else -> null
        }
        return PracticeSession(id(1, index), anchor, anchor + length, length, true, false,
            if (dst != null || index % 72 == 0) "Europe/Warsaw" else "UTC", config, timeline)
    }

    private suspend fun measure(dir: File, phase: String, add: (JSONObject) -> Unit,
        action: suspend (Gauge, JSONObject) -> Unit) = coroutineScope {
        val result = JSONObject().put("phase", phase)
        val gauge = Gauge(dir)
        gauge.capture()
        val start = now()
        val sampler = launch(Dispatchers.Default) {
            while (isActive) { delay(50); gauge.capture() }
        }
        try { action(gauge, result) }
        finally {
            val elapsed = now() - start
            sampler.cancelAndJoin()
            gauge.capture()
            result.put("elapsedMs", ms(elapsed)).put("resources", gauge.result())
            add(result)
        }
    }

    private data class Reading(val heap: Long, val pss: Long, val db: Long, val wal: Long,
        val archive: Long, val temporary: Long, val staging: Long)

    private class Gauge(private val dir: File) {
        private var first: Reading? = null
        private var last: Reading? = null
        private var max: Reading? = null
        private var samples = 0

        @Synchronized fun capture() {
            val runtime = Runtime.getRuntime()
            val reading = Reading(runtime.totalMemory() - runtime.freeMemory(), Debug.getPss() * 1024,
                File(dir, "practice.db").length(), File(dir, "practice.db-wal").length(),
                File(dir, "export.ombak").length(),
                File(dir, "restore-journal").walkTopDown().filter { it.isFile }.sumOf { it.length() },
                File(dir, "backup-staging").walkTopDown().filter { it.isFile }.sumOf { it.length() })
            if (first == null) first = reading
            last = reading
            max = max?.let { previous ->
                Reading(maxOf(previous.heap, reading.heap), maxOf(previous.pss, reading.pss), maxOf(previous.db, reading.db),
                    maxOf(previous.wal, reading.wal), maxOf(previous.archive, reading.archive), maxOf(previous.temporary, reading.temporary),
                    maxOf(previous.staging, reading.staging))
            } ?: reading
            samples++
        }

        fun result() = JSONObject().put("first", fields(first!!)).put("last", fields(last!!))
            .put("peak", fields(max!!)).put("samples", samples)
        private fun fields(r: Reading) = JSONObject().put("heapBytes", r.heap).put("pssBytes", r.pss)
            .put("dbBytes", r.db).put("walBytes", r.wal).put("archiveBytes", r.archive).put("restoreWorkBytes", r.temporary).put("validationStagingBytes", r.staging)
    }

    companion object {
        private val START = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli()
        private val DST_SESSIONS = listOf("2020-03-29T00:55:00Z", "2020-10-25T00:55:00Z")
            .map { Instant.parse(it).toEpochMilli() }
        private fun id(kind: Int, n: Int) = UUID(kind.toLong(), n + 1L).toString()
        private fun now() = SystemClock.elapsedRealtimeNanos()
        private fun ms(nanos: Long) = nanos / 1_000_000.0
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
