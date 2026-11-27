package org.openmetronome.domain.fit

import java.io.InputStream
import org.openmetronome.domain.model.MusicActivityDetails
import org.openmetronome.domain.model.MusicActivityInterval
import kotlin.math.abs

internal enum class FitSessionProblem {
    MISSING_SUMMARY, INVALID_SUMMARY, MISSING_TIMER_BOUNDARY, AMBIGUOUS_TIMER,
    OVERLAPPING_SESSIONS, SUMMARY_END_MISMATCH, TIMER_TOTAL_MISMATCH, TOO_MANY_INTERVALS,
}

internal data class FitSessionSummary(
    val startAt: Long,
    val endAt: Long,
    val reportedElapsedMs: Long,
    val reportedTimerMs: Long,
    val reportedEndAt: Long?,
    val sport: Int?,
    val subSport: Int?,
)

internal sealed interface FitSession {
    /** Zero-based ordinal of the Session data message in wire order, including rejected sessions. */
    val ordinal: Int

    data class Complete(
        override val ordinal: Int,
        val summary: FitSessionSummary,
        val intervals: List<MusicActivityInterval>,
    ) : FitSession {
        val intervalResolutionMs: Long get() = 1_000
    }

    data class SummaryOnly(override val ordinal: Int, val summary: FitSessionSummary,
        val reason: FitSessionProblem) : FitSession

    data class Invalid(override val ordinal: Int, val reason: FitSessionProblem) : FitSession
}

internal data class FitActivityFile(val sha256: String, val sessions: List<FitSession>)

/** Projects a validated Activity container to time data. No health, device, or location fields are retained. */
internal object FitActivityReader {
    private const val FIT_EPOCH_MS = 631_065_600_000L
    private const val MIN_ABSOLUTE_TIMESTAMP_SECONDS = 0x1000_0000L
    private const val QUANTUM_MS = 1_000L

    fun read(input: InputStream, budget: FitReadBudget = FitReadBudget(),
        checkCancelled: () -> Unit = {}): FitActivityFile {
        val projection = Projection(budget)
        val sha = FitBinaryReader.read(input, budget, checkCancelled, projection::accept)
        return FitActivityFile(sha, projection.sessions(checkCancelled))
    }

    private enum class TimerAction { START, STOP, STOP_ALL, UNKNOWN }
    private data class TimerEvent(val at: Long, val action: TimerAction)
    private data class Candidate(val ordinal: Int, val summary: FitSessionSummary, val supportedTimerGroup: Boolean)

    private class Projection(private val budget: FitReadBudget) {
        private var firstDataNumber: Int? = null
        private var fileIdCount = 0
        private var fileType: Long? = null
        private var activityCount = 0
        private var expectedSessions: Long? = null
        private var sessionCount = 0
        private var timerEventCount = 0
        private var unplacedTimerEvent = false
        private val candidates = mutableListOf<Candidate>()
        private val invalid = mutableListOf<FitSession.Invalid>()
        private val events = mutableListOf<TimerEvent>()

        fun accept(message: FitMessage) {
            if (firstDataNumber == null) firstDataNumber = message.number
            when (message.number) {
                FitBinaryReader.FILE_ID -> {
                    fileIdCount++
                    fileType = message[0]
                }
                FitBinaryReader.ACTIVITY -> {
                    activityCount++
                    expectedSessions = message[1]
                }
                FitBinaryReader.SESSION -> {
                    budget.consumeSession()
                    addSession(sessionCount++, message)
                }
                FitBinaryReader.EVENT -> addEvent(message)
            }
        }

        private fun addSession(ordinal: Int, message: FitMessage) {
            val start = absoluteTime(message[2])
            val elapsed = message[7]
            val timer = message[8]
            if (start == null || elapsed == null || timer == null) {
                invalid += FitSession.Invalid(ordinal, FitSessionProblem.MISSING_SUMMARY)
            } else if (elapsed <= 0 || timer !in 1..elapsed) {
                invalid += FitSession.Invalid(ordinal, FitSessionProblem.INVALID_SUMMARY)
            } else {
                val summary = FitSessionSummary(start, start + elapsed, elapsed, timer,
                    absoluteTime(message[253]), message[5]?.toInt(), message[6]?.toInt())
                candidates += Candidate(ordinal, summary, (message[27] ?: 0L) == 0L)
            }
        }

        private fun addEvent(message: FitMessage) {
            val type = message[0]
            if (type != null && type != 0L) return
            if (++timerEventCount > budget.limits.timerEventsPerFile) fitFailure(FitFailureKind.LIMIT, "Too many FIT timer events")
            val at = absoluteTime(message[253])
            if (at == null) {
                unplacedTimerEvent = true
                return
            }
            val action = if (type == null || (message[4] ?: 0L) != 0L) TimerAction.UNKNOWN else when (message[1]) {
                0L -> TimerAction.START
                1L -> TimerAction.STOP
                4L -> TimerAction.STOP_ALL
                else -> TimerAction.UNKNOWN
            }
            events += TimerEvent(at, action)
        }

        fun sessions(checkCancelled: () -> Unit): List<FitSession> {
            if (firstDataNumber != FitBinaryReader.FILE_ID || fileIdCount != 1 || fileType == null) {
                fitFailure(FitFailureKind.INCONSISTENT, "FIT file identification is missing or ambiguous")
            }
            if (fileType != 4L) fitFailure(FitFailureKind.UNSUPPORTED, "FIT file is not an Activity")
            if (sessionCount == 0 || activityCount > 1 ||
                (expectedSessions != null && expectedSessions != sessionCount.toLong())) {
                fitFailure(FitFailureKind.INCONSISTENT, "FIT session summaries are missing or inconsistent")
            }

            // Stable sort: the original order still decides start/stop at the same second.
            events.sortBy(TimerEvent::at)
            val ordered = candidates.sortedWith(compareBy<Candidate> { it.summary.startAt }.thenBy { it.ordinal })
            var previousMaxEnd: Long? = null
            return (ordered.mapIndexed { index, candidate ->
                checkCancelled()
                val previousEnd = previousMaxEnd
                val summary = candidate.summary
                previousMaxEnd = maxOf(previousEnd ?: summary.endAt, summary.endAt)
                val nextStart = ordered.getOrNull(index + 1)?.summary?.startAt
                val overlap = (previousEnd != null && previousEnd - QUANTUM_MS > summary.startAt) ||
                    (nextStart != null && (nextStart == summary.startAt || summary.endAt - QUANTUM_MS > nextStart))
                when {
                    overlap -> candidate.incomplete(FitSessionProblem.OVERLAPPING_SESSIONS)
                    summary.reportedEndAt != null && abs(summary.reportedEndAt - summary.endAt) > QUANTUM_MS ->
                        candidate.incomplete(FitSessionProblem.SUMMARY_END_MISMATCH)
                    !candidate.supportedTimerGroup || unplacedTimerEvent || invalid.isNotEmpty() && sessionCount > 1 ->
                        candidate.incomplete(FitSessionProblem.AMBIGUOUS_TIMER)
                    else -> reconstruct(candidate, previousEnd, nextStart, checkCancelled)
                }
            } + invalid).sortedBy { it.ordinal }
        }

        private fun reconstruct(candidate: Candidate, previousEnd: Long?, nextStart: Long?,
            checkCancelled: () -> Unit): FitSession {
            val summary = candidate.summary
            val endLimit = minOf(nextStart ?: Long.MAX_VALUE, summary.endAt + QUANTUM_MS)
            val intervals = mutableListOf<MusicActivityInterval>()
            var start: Long? = null
            var last: TimerEvent? = null
            var index = lowerBound(summary.startAt)
            var sum = 0L
            while (index < events.size) {
                checkCancelled()
                val event = events[index++]
                if (event.at > endLimit) break
                val stopping = event.action == TimerAction.STOP || event.action == TimerAction.STOP_ALL
                // At a shared boundary, stop belongs to the older session and start to the newer.
                if (event.at == summary.startAt && previousEnd != null && previousEnd + QUANTUM_MS >= event.at && stopping) continue
                if (event.at == nextStart && event.action == TimerAction.START) continue
                // Outside the summary window only a closing edge within the timestamp quantum is usable.
                if (event.at > summary.endAt && !stopping) {
                    if (start != null || event.action == TimerAction.UNKNOWN) {
                        return candidate.incomplete(FitSessionProblem.AMBIGUOUS_TIMER)
                    }
                    continue
                }
                val prior = last
                val duplicateEdge = prior?.at == event.at &&
                    (prior.action == event.action || stopping && (prior.action == TimerAction.STOP || prior.action == TimerAction.STOP_ALL))
                if (duplicateEdge) continue
                last = event
                when (event.action) {
                    TimerAction.START -> {
                        if (start != null) return candidate.incomplete(FitSessionProblem.AMBIGUOUS_TIMER)
                        start = event.at
                    }
                    TimerAction.STOP, TimerAction.STOP_ALL -> {
                        val openedAt = start ?: return candidate.incomplete(FitSessionProblem.MISSING_TIMER_BOUNDARY)
                        if (event.at <= openedAt) return candidate.incomplete(FitSessionProblem.AMBIGUOUS_TIMER)
                        if (intervals.size >= MusicActivityDetails.MAX_INTERVALS) {
                            return candidate.incomplete(FitSessionProblem.TOO_MANY_INTERVALS)
                        }
                        intervals += MusicActivityInterval(openedAt, event.at)
                        sum += event.at - openedAt
                        start = null
                    }
                    TimerAction.UNKNOWN -> return candidate.incomplete(FitSessionProblem.AMBIGUOUS_TIMER)
                }
            }
            if (start != null || intervals.isEmpty()) return candidate.incomplete(FitSessionProblem.MISSING_TIMER_BOUNDARY)
            if (abs(summary.reportedTimerMs - sum) > intervals.size * QUANTUM_MS) {
                return candidate.incomplete(FitSessionProblem.TIMER_TOTAL_MISMATCH)
            }
            // Extend only the containing window if a real stop marker is marginally later.
            // Neither that marker nor the reported sums are changed.
            val end = maxOf(summary.endAt, intervals.last().endAt)
            return FitSession.Complete(candidate.ordinal, summary.copy(endAt = end), intervals)
        }

        private fun lowerBound(at: Long): Int {
            var low = 0
            var high = events.size
            while (low < high) {
                val mid = (low + high) / 2
                if (events[mid].at < at) low = mid + 1 else high = mid
            }
            return low
        }

        private fun Candidate.incomplete(reason: FitSessionProblem) = FitSession.SummaryOnly(ordinal, summary, reason)
    }

    private fun absoluteTime(seconds: Long?): Long? =
        seconds?.takeIf { it >= MIN_ABSOLUTE_TIMESTAMP_SECONDS }?.let { FIT_EPOCH_MS + it * 1_000 }
}
