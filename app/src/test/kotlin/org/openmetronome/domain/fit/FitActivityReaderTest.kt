package org.openmetronome.domain.fit

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MusicActivityDetails
import org.openmetronome.domain.model.MusicActivityKind
import org.openmetronome.domain.model.MusicActivitySource

class FitActivityReaderTest {
    @Test fun retainsReportedAndReconstructedDurationsSeparately() {
        val continuous = complete("continuous")
        assertEquals(130_051L, continuous.summary.reportedElapsedMs)
        assertEquals(130_051L, continuous.summary.reportedTimerMs)
        assertEquals(130_000L, continuous.intervals.single().duration())
        assertEquals(130_051L, continuous.summary.endAt - continuous.summary.startAt)

        val paused = complete("paused-big-endian")
        assertEquals(196_565L, paused.summary.reportedElapsedMs)
        assertEquals(133_360L, paused.summary.reportedTimerMs)
        assertEquals(listOf(61_000L, 72_000L), paused.intervals.map { it.duration() })
        assertEquals(64_000L, paused.intervals[1].startAt - paused.intervals[0].endAt)
        assertEquals(197_000L, paused.summary.endAt - paused.summary.startAt)
        assertEquals(1_000L, paused.intervalResolutionMs)

        for (session in listOf(continuous, paused)) {
            MusicActivityDetails(MusicActivityKind.LESSON, MusicActivitySource.FIT, session.summary.startAt,
                session.summary.endAt, "UTC", session.summary.reportedTimerMs, session.intervalResolutionMs, session.intervals)
        }
    }

    @Test fun handlesCompressedRolloverAndDeveloperFieldsAcrossRedefinition() {
        val file = read(fixture("compressed-developer"))
        val session = file.sessions.single() as FitSession.Complete
        assertEquals(1_781_065_628_000L, session.intervals.single().startAt)
        assertEquals(6_000L, session.intervals.single().duration())
        assertEquals(0, session.summary.sport)
        assertEquals(0, session.summary.subSport)
        assertEquals(sha256(fixture("compressed-developer")), file.sha256)
    }

    @Test fun refusesCompressedRecordsWithoutTimeReferenceAndMalformedDeveloperDefinitions() {
        val payload = body(fixture("compressed-developer"))
        val anchor = payload.indexOf(0x41.toByte()) + 6 + 3 * 3
        val withoutAnchor = payload.copyOfRange(0, anchor) + payload.copyOfRange(anchor + 7, payload.size)
        fails(FitFailureKind.CORRUPT, container(withoutAnchor))
        val developer = payload.indexOf(0x61.toByte()) + 6 + 2 * 3 + 1
        fails(FitFailureKind.CORRUPT, container(payload.copyOf().apply { this[developer + 1] = 0 }))
        fails(FitFailureKind.UNSUPPORTED, container(payload, protocol = 0x10))

        val withFullTimestamp = body(fixture("continuous"))
        val firstEvent = withFullTimestamp.indexOf(0x41.toByte()) + 6 + 3 * 3
        withFullTimestamp[firstEvent + 7] = 0xA0.toByte()
        fails(FitFailureKind.UNSUPPORTED, container(withFullTimestamp))
    }

    @Test fun usesWireOrdinalsAndAssignsSharedBoundaryToBothSessionsByEdgeType() {
        val raw = fixture("multiple-reordered")
        val sessions = read(raw).sessions.map { it as FitSession.Complete }
        assertEquals(listOf(0, 1), sessions.map { it.ordinal })
        assertEquals(sessions[0].intervals.single().startAt, sessions[1].intervals.single().endAt)
        assertEquals(listOf(60_000L, 60_000L), sessions.map { it.intervals.single().duration() })

        val body = body(raw)
        val summary = sessionRecords(body)
        body.setU32(summary + 9, 0xFFFF_FFFFL)
        val invalid = read(container(body)).sessions
        assertEquals(FitSession.Invalid(0, FitSessionProblem.MISSING_SUMMARY), invalid[0])
        assertEquals(1, invalid[1].ordinal)
    }

    @Test fun startBeforeStopAtSharedBoundaryCannotEstablishEitherSession() {
        val payload = body(fixture("multiple-reordered"))
        val boundaryStop = eventRecord(60, 1)
        val boundaryStart = eventRecord(60, 0)
        val stopAt = payload.indices.first { payload.copyOfRange(it, minOf(it + boundaryStop.size, payload.size))
            .contentEquals(boundaryStop) }
        boundaryStart.copyInto(payload, stopAt)
        boundaryStop.copyInto(payload, stopAt + boundaryStart.size)

        val sessions = read(container(payload)).sessions
        assertEquals(listOf(0, 1), sessions.map { it.ordinal })
        assertTrue(sessions.all {
            it is FitSession.SummaryOnly && it.reason == FitSessionProblem.AMBIGUOUS_TIMER &&
                it.summary.reportedTimerMs == 60_000L
        })
    }

    @Test fun missingOrAmbiguousTimerEdgesProduceSummaryWithoutInventingIntervals() {
        val incomplete = read(fixture("summary-only")).sessions.single() as FitSession.SummaryOnly
        assertEquals(FitSessionProblem.MISSING_TIMER_BOUNDARY, incomplete.reason)
        assertEquals(130_051L, incomplete.summary.reportedTimerMs)
        val body = body(fixture("continuous"))
        val index = body.indexOfFirst { it == 0x42.toByte() }
        val secondStart = eventRecord(15, 0)
        val extraStop = eventRecord(131, 1)
        val unknown = eventRecord(70, 8)
        for ((event, reason) in listOf(secondStart to FitSessionProblem.AMBIGUOUS_TIMER,
            extraStop to FitSessionProblem.MISSING_TIMER_BOUNDARY, unknown to FitSessionProblem.AMBIGUOUS_TIMER)) {
            val changed = body.copyOfRange(0, index) + event + body.copyOfRange(index, body.size)
            assertEquals(reason, (read(container(changed)).sessions.single() as FitSession.SummaryOnly).reason)
        }
        val duplicate = body.copyOfRange(0, index) + eventRecord(130, 1) + body.copyOfRange(index, body.size)
        assertEquals(130_000L, (read(container(duplicate)).sessions.single() as FitSession.Complete).intervals.single().duration())
        val invalidTime = eventRecord(0, 0).apply { setU32(1, 0xFFFF_FFFFL) }
        val unplaced = body.copyOfRange(0, index) + invalidTime + body.copyOfRange(index, body.size)
        assertEquals(FitSessionProblem.AMBIGUOUS_TIMER, (read(container(unplaced)).sessions.single() as FitSession.SummaryOnly).reason)
    }

    @Test fun contradictorySummariesAndMismatchedTimerTotalsAreDistinct() {
        val base = body(fixture("continuous"))
        val summary = sessionRecords(base)
        val timerTooLong = base.copyOf().apply { setU32(summary + 13, 140_000) }
        assertEquals(FitSession.Invalid(0, FitSessionProblem.INVALID_SUMMARY), read(container(timerTooLong)).sessions.single())
        val incompatibleIntervals = base.copyOf().apply { setU32(summary + 13, 100_000) }
        val incomplete = read(container(incompatibleIntervals)).sessions.single() as FitSession.SummaryOnly
        assertEquals(100_000L, incomplete.summary.reportedTimerMs)
        assertEquals(FitSessionProblem.TIMER_TOTAL_MISMATCH, incomplete.reason)
        val changedCount = base.copyOf().apply { this[lastIndex - 1] = 2 }
        fails(FitFailureKind.INCONSISTENT, container(changedCount))
        val contradictoryEnd = base.copyOf().apply { setU32(summary + 1, 1_150_000_200L) }
        assertEquals(FitSessionProblem.SUMMARY_END_MISMATCH,
            (read(container(contradictoryEnd)).sessions.single() as FitSession.SummaryOnly).reason)
    }

    @Test fun overlappingSessionWindowsDoNotReuseTheSameTimerEvents() {
        val base = body(fixture("multiple-reordered"))
        base.setU32(sessionRecords(base) + 5, 1_150_000_050L)
        val sessions = read(container(base)).sessions
        assertEquals(listOf(FitSessionProblem.OVERLAPPING_SESSIONS, FitSessionProblem.OVERLAPPING_SESSIONS),
            sessions.map { (it as FitSession.SummaryOnly).reason })
    }

    @Test fun distinguishesCorruptionFromUnsupportedFormatAndEnforcesScalarTypes() {
        val raw = fixture("continuous")
        fails(FitFailureKind.CORRUPT, raw.copyOf(raw.size - 1))
        fails(FitFailureKind.CORRUPT, raw.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() })
        fails(FitFailureKind.CORRUPT, raw.copyOf().apply { this[12] = (this[12].toInt() xor 1).toByte() })
        fails(FitFailureKind.CORRUPT, raw + byteArrayOf(12)) // a partial trailing header is still corruption
        fails(FitFailureKind.UNSUPPORTED, raw + raw)
        fails(FitFailureKind.CORRUPT, raw.copyOf().apply { this[1] = 0x30 })
        val data = body(raw)
        fails(FitFailureKind.UNSUPPORTED, container(data, protocol = 0x30))
        fails(FitFailureKind.UNSUPPORTED, container(data.copyOf().apply { this[10] = 6 }))
        fails(FitFailureKind.CORRUPT, container(data.copyOf().apply { this[8] = 2 }))
        fails(FitFailureKind.CORRUPT, container(byteArrayOf(0x00)))
        fails(FitFailureKind.UNSUPPORTED, container(data.copyOf().apply { this[13] = 3 }))
        fails(FitFailureKind.CORRUPT, container(data.copyOf().apply { this[7] = 0 }))
    }

    @Test fun limitsBytesFilesSessionsRecordsAndEventsAndSupportsCancellation() {
        val raw = fixture("continuous")
        fun limits(limits: FitReadLimits) = assertEquals(FitFailureKind.LIMIT,
            assertThrows(FitReadException::class.java) { read(raw, FitReadBudget(limits)) }.kind)
        limits(FitReadLimits(fileBytes = raw.size - 1))
        limits(FitReadLimits(batchBytes = raw.size - 1))
        limits(FitReadLimits(recordsPerFile = 3))
        limits(FitReadLimits(timerEventsPerFile = 1))
        assertEquals(1, read(raw, FitReadBudget(FitReadLimits(fileBytes = raw.size, batchBytes = raw.size))).sessions.size)
        val sessions = FitReadBudget(FitReadLimits(sessions = 1))
        read(raw, sessions)
        assertEquals(FitFailureKind.LIMIT, assertThrows(FitReadException::class.java) { read(raw, sessions) }.kind)
        val files = FitReadBudget(FitReadLimits(files = 1))
        read(raw, files)
        assertEquals(FitFailureKind.LIMIT, assertThrows(FitReadException::class.java) { read(raw, files) }.kind)

        var bytesRead = 0
        val source = object : InputStream() {
            override fun read(): Int = error("Reader must buffer sequential reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(0, offset, offset + length)
                bytesRead += length
                return length
            }
        }
        assertEquals(FitFailureKind.LIMIT, assertThrows(FitReadException::class.java) {
            FitActivityReader.read(source, FitReadBudget(FitReadLimits(batchBytes = 64)))
        }.kind)
        assertEquals(65, bytesRead)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            FitActivityReader.read(ByteArrayInputStream(raw)) { if (++checks == 5) throw CancellationException() }
        }
        assertThrows(IOException::class.java) {
            FitActivityReader.read(object : InputStream() { override fun read(): Int = throw IOException("unavailable") })
        }
    }

    @Test fun deterministicMutationsNeverExposePartialResultsOrUnexpectedFailures() {
        val random = Random(0x117FA)
        for (name in listOf("continuous", "paused-big-endian", "compressed-developer", "multiple-reordered")) {
            val raw = fixture(name)
            for (size in raw.indices) {
                assertThrows(FitReadException::class.java) { read(raw.copyOf(size)) }
            }
            repeat(250) {
                val damaged = raw.copyOf()
                val position = random.nextInt(12, raw.size)
                damaged[position] = (damaged[position].toInt() xor (1 shl random.nextInt(8))).toByte()
                assertThrows(FitReadException::class.java) { read(damaged) }
                val payload = body(raw)
                repeat(random.nextInt(1, 7)) { payload[random.nextInt(payload.size)] = random.nextInt(256).toByte() }
                val validCrc = container(payload)
                try {
                    val accepted = read(validCrc)
                    assertEquals(sha256(validCrc), accepted.sha256)
                    accepted.sessions.filterIsInstance<FitSession.Complete>().forEach {
                        MusicActivityDetails(MusicActivityKind.LESSON, MusicActivitySource.FIT, it.summary.startAt,
                            it.summary.endAt, "UTC", it.summary.reportedTimerMs, it.intervalResolutionMs, it.intervals)
                    }
                } catch (_: FitReadException) {
                    // A recomputed CRC does not make an invalid record safe.
                }
            }
        }
    }

    @Test fun batchOverrunChargesFetchedBytesAndPreventsAnyFurtherReads() {
        val raw = fixture("continuous")
        val budget = FitReadBudget(FitReadLimits(batchBytes = raw.size + 48))
        read(raw, budget)
        assertEquals(48, budget.remainingBytes)

        val overrun = CountingStream(ByteArray(64))
        assertEquals(FitFailureKind.LIMIT, assertThrows(FitReadException::class.java) {
            FitActivityReader.read(overrun, budget)
        }.kind)
        assertEquals(49, overrun.bytesRead)

        val subsequent = listOf(CountingStream(raw), CountingStream(ByteArray(64)))
        val errors = subsequent.map { stream ->
            assertThrows(FitReadException::class.java) { FitActivityReader.read(stream, budget) }.kind
        }
        assertEquals(listOf(FitFailureKind.LIMIT, FitFailureKind.LIMIT), errors)
        assertEquals(listOf(0, 0), subsequent.map { it.readCalls })
        assertEquals(0, budget.remainingBytes)
    }

    @Test fun batchLimitAllowsExactEofProbeOnlyForTheCurrentFile() {
        val raw = fixture("continuous")
        val budget = FitReadBudget(FitReadLimits(fileBytes = raw.size, batchBytes = raw.size * 2))
        read(raw, budget)
        val last = CountingStream(raw)
        assertEquals(sha256(raw), FitActivityReader.read(last, budget).sha256)
        assertEquals(raw.size, last.bytesRead)
        assertEquals(listOf(1), last.eofReadSizes)

        val next = CountingStream(raw)
        assertEquals(FitFailureKind.LIMIT, assertThrows(FitReadException::class.java) {
            FitActivityReader.read(next, budget)
        }.kind)
        assertEquals(0, next.readCalls)
    }

    private class CountingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var readCalls = 0
            private set
        var bytesRead = 0
            private set
        val eofReadSizes = mutableListOf<Int>()

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            val count = super.read(bytes, offset, length)
            if (count < 0) eofReadSizes += length else bytesRead += count
            return count
        }
    }

    private fun fails(kind: FitFailureKind, bytes: ByteArray) =
        assertEquals(kind, assertThrows(FitReadException::class.java) { read(bytes) }.kind)

    private fun complete(name: String) = read(fixture(name)).sessions.single() as FitSession.Complete

    private fun read(bytes: ByteArray, budget: FitReadBudget = FitReadBudget()) =
        FitActivityReader.read(ByteArrayInputStream(bytes), budget)

    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/fit/$name.fit")).use { it.readBytes() }

    private fun org.openmetronome.domain.model.MusicActivityInterval.duration() = endAt - startAt

    private fun body(fit: ByteArray) = fit.copyOfRange(fit[0].toInt(), fit.size - 2)

    private fun sessionRecords(body: ByteArray) = body.indexOfFirst { it == 0x42.toByte() } + 6 + 6 * 3

    private fun eventRecord(seconds: Int, type: Int) = byteArrayOf(1, 0, 0, 0, 0, 0, type.toByte()).apply {
        setU32(1, 1_150_000_000L + seconds)
    }

    private fun ByteArray.setU32(index: Int, value: Long) {
        for (byte in 0..3) this[index + byte] = (value ushr (8 * byte)).toByte()
    }

    private fun container(body: ByteArray, protocol: Byte = 0x20): ByteArray {
        val payload = byteArrayOf(12, protocol, 0, 0, 0, 0, 0, 0, 46, 70, 73, 84) + body
        payload.setU32(4, body.size.toLong())
        var crc = 0
        for (byte in payload) {
            crc = crc xor (byte.toInt() and 255)
            repeat(8) { crc = (crc ushr 1) xor if (crc and 1 == 0) 0 else 0xA001 }
        }
        return payload + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
