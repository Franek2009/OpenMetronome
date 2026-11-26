package org.openmetronome.data

import java.time.Instant
import java.util.PriorityQueue
import java.util.UUID

enum class PracticeGroupingGap(val minutes: Int?) {
    DISABLED(null), MINUTES_5(5), MINUTES_15(15), MINUTES_30(30), MINUTES_60(60);

    companion object { val DEFAULT = MINUTES_30 }
}

sealed interface PracticeGroupingChange {
    data class FinishAfter(val playbackId: String) : PracticeGroupingChange
    data class SplitBefore(val playbackId: String) : PracticeGroupingChange
    data class Join(val leftPlaybackId: String, val rightPlaybackId: String) : PracticeGroupingChange
    data class ResetGroup(val playbackId: String) : PracticeGroupingChange
    data object ResetAll : PracticeGroupingChange
}

data class PracticeGroup(
    val playbackIds: List<String>, val startedAt: Long, val endedAt: Long, val playbackMs: Long, val completed: Boolean,
) {
    val spanMs: Long get() = Math.subtractExact(endedAt, startedAt)
}

data class PracticeGroupingSummary(val groups: List<PracticeGroup>) {
    val averageCompletedPlaybackMs: Long? get() = average(groups.asSequence())

    fun averageCompletedPlaybackMsForStarts(fromInclusive: Long, toExclusive: Long): Long? =
        average(groups.asSequence().filter { it.startedAt >= fromInclusive && it.startedAt < toExclusive })

    private fun average(groups: Sequence<PracticeGroup>): Long? {
        var count = 0L
        var total = 0L
        groups.filter { it.completed }.forEach { group -> count++; total = Math.addExact(total, group.playbackMs) }
        return if (count == 0L) null else total / count
    }
}

@ConsistentCopyVisibility
data class PracticeGroupingPreview internal constructor(
    val change: PracticeGroupingChange, val before: List<PracticeGroup>, val after: List<PracticeGroup>,
    internal val correctionsToReset: Set<String>, internal val gap: PracticeGroupingGap, internal val at: Instant,
) {
    val resetCount: Int get() = correctionsToReset.size
}

internal data class GroupingPlayback(val id: String, val startedAt: Long, val durationMs: Long, val finished: Boolean) {
    val endedAt: Long get() = Math.addExact(startedAt, durationMs)
}

enum class GroupingCorrectionKind { JOIN, SPLIT_BEFORE, FINISH_AFTER }

data class GroupingCorrection(
    val id: String, val sequenceNumber: Long, val kind: GroupingCorrectionKind,
    val playbackId: String, val otherPlaybackId: String? = null, val deletedAt: Long? = null,
) {
    init {
        validateUuid(id)
        validateUuid(playbackId)
        require(sequenceNumber > 0)
        require((kind == GroupingCorrectionKind.JOIN) == (otherPlaybackId != null))
        otherPlaybackId?.let { validateUuid(it); require(it != playbackId) }
    }

    private fun validateUuid(value: String) { require(UUID.fromString(value).toString() == value) }
}

internal class PracticeGrouping(
    playbacks: List<GroupingPlayback>, private val corrections: List<GroupingCorrection>,
    private val gap: PracticeGroupingGap, private val at: Instant,
) {
    private val ordered = playbacks.filter { it.durationMs > 0 }.sortedWith(compareBy(GroupingPlayback::startedAt, GroupingPlayback::id))
    private val positions = ordered.mapIndexed { index, playback -> playback.id to index }.toMap()
    private val active = corrections.filter { it.deletedAt == null }

    fun summarize(): PracticeGroupingSummary = project(active)

    data class Plan(val preview: PracticeGroupingPreview, val addition: GroupingCorrection?)

    fun plan(change: PracticeGroupingChange): Plan {
        val before = project(active).groups
        fun index(id: String) = requireNotNull(positions[id]) { "Unknown playback" }
        fun correction(kind: GroupingCorrectionKind, id: String, other: String? = null) =
            GroupingCorrection(UUID.randomUUID().toString(), Math.addExact(corrections.maxOfOrNull { it.sequenceNumber } ?: 0L, 1L), kind, id, other)
        val addition = when (change) {
            is PracticeGroupingChange.FinishAfter -> {
                val playback = index(change.playbackId)
                require(ordered[playback].finished) { "Playback is not finalized" }
                val boundary = playback + 1
                val winner = ranges(active).asSequence().filter { boundary in it.first..it.last }
                    .maxByOrNull { it.correction.sequenceNumber }
                if (winner?.correction?.kind == GroupingCorrectionKind.FINISH_AFTER) null
                else correction(GroupingCorrectionKind.FINISH_AFTER, change.playbackId)
            }
            is PracticeGroupingChange.SplitBefore -> {
                require(index(change.playbackId) > 0) { "There is no preceding playback" }
                correction(GroupingCorrectionKind.SPLIT_BEFORE, change.playbackId)
            }
            is PracticeGroupingChange.Join -> {
                require(index(change.leftPlaybackId) + 1 == index(change.rightPlaybackId)) { "Playbacks are not adjacent" }
                val left = requireNotNull(before.firstOrNull { it.playbackIds.last() == change.leftPlaybackId }) {
                    "Playbacks are already in one group"
                }
                val right = before.first { it.playbackIds.first() == change.rightPlaybackId }
                correction(GroupingCorrectionKind.JOIN, left.playbackIds.first(), right.playbackIds.last())
            }
            else -> null
        }
        val resets = when (change) {
            PracticeGroupingChange.ResetAll -> active.mapTo(hashSetOf()) { it.id }
            is PracticeGroupingChange.ResetGroup -> {
                val selected = requireNotNull(before.firstOrNull { change.playbackId in it.playbackIds }) { "Unknown playback" }
                val first = index(selected.playbackIds.first())
                val last = index(selected.playbackIds.last()) + 1
                ranges(active).filter { it.first <= last && it.last >= first }.mapTo(hashSetOf()) { it.correction.id }
            }
            else -> emptySet()
        }
        val after = project(active.filterNot { it.id in resets } + listOfNotNull(addition)).groups
        val beforeSet = before.toHashSet()
        val afterSet = after.toHashSet()
        return Plan(PracticeGroupingPreview(change, before.filterNot { it in afterSet }, after.filterNot { it in beforeSet },
            resets, gap, at), addition)
    }

    private data class BoundaryRange(val first: Int, val last: Int, val correction: GroupingCorrection)

    private fun ranges(rules: List<GroupingCorrection>) = rules.mapNotNull { rule ->
        val anchor = positions[rule.playbackId] ?: return@mapNotNull null
        when (rule.kind) {
            GroupingCorrectionKind.FINISH_AFTER -> BoundaryRange(anchor + 1, anchor + 1, rule)
            GroupingCorrectionKind.SPLIT_BEFORE -> BoundaryRange(anchor, anchor, rule).takeIf { anchor > 0 }
            GroupingCorrectionKind.JOIN -> rule.otherPlaybackId?.let(positions::get)?.takeIf { it > anchor }
                ?.let { BoundaryRange(anchor + 1, it, rule) }
        }
    }

    private fun project(rules: List<GroupingCorrection>): PracticeGroupingSummary {
        if (ordered.isEmpty()) return PracticeGroupingSummary(emptyList())
        val candidates = ranges(rules).sortedBy { it.first }
        val current = PriorityQueue(compareByDescending<BoundaryRange> { it.correction.sequenceNumber })
        var cursor = 0
        fun boundary(index: Int): GroupingCorrectionKind? {
            while (cursor < candidates.size && candidates[cursor].first <= index) current.add(candidates[cursor++])
            while (current.peek()?.let { it.last < index } == true) current.remove()
            return current.peek()?.correction?.kind
        }

        val groups = mutableListOf<PracticeGroup>()
        val gapMs = gap.minutes?.times(60_000L)
        var first = 0
        var latestEnd = ordered[0].endedAt
        var total = ordered[0].durationMs
        var finalized = ordered[0].finished
        fun append(until: Int, closed: Boolean) {
            groups.add(PracticeGroup(ordered.subList(first, until).map { it.id }, ordered[first].startedAt,
                latestEnd, total, finalized && closed))
        }
        for (index in 1 until ordered.size) {
            val next = ordered[index]
            val split = when (boundary(index)) {
                GroupingCorrectionKind.JOIN -> false
                GroupingCorrectionKind.SPLIT_BEFORE, GroupingCorrectionKind.FINISH_AFTER -> true
                null -> gapMs == null || afterGap(next.startedAt, latestEnd, gapMs)
            }
            if (split) {
                append(index, closed = true)
                first = index
                latestEnd = next.endedAt
                total = next.durationMs
                finalized = next.finished
            } else {
                latestEnd = maxOf(latestEnd, next.endedAt)
                total = Math.addExact(total, next.durationMs)
                finalized = finalized && next.finished
            }
        }
        val closed = boundary(ordered.size) == GroupingCorrectionKind.FINISH_AFTER ||
            gapMs == null || afterGap(at.toEpochMilli(), latestEnd, gapMs)
        append(ordered.size, closed)
        return PracticeGroupingSummary(groups)
    }

    private fun afterGap(start: Long, end: Long, gapMs: Long) = end <= Long.MAX_VALUE - gapMs && start > end + gapMs
}
