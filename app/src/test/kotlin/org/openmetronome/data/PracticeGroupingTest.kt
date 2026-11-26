package org.openmetronome.data

import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PracticeGroupingTest {
    @Test fun allThresholdsIncludeTheExactGapAndDisabledKeepsSeparatePlaybacks() {
        val first = playback(1, 0, 10_000)
        for (gap in PracticeGroupingGap.entries) {
            val offset = (gap.minutes ?: 0) * 60_000L
            val atLimit = playback(2, first.endedAt + offset)
            val beyond = playback(3, atLimit.endedAt + offset + 1)
            val groups = project(listOf(beyond, first, atLimit), gap = gap).groups
            assertEquals(if (gap.minutes == null) 3 else 2, groups.size)
            assertEquals(beyond.id, groups.last().playbackIds.single())
        }
        assertEquals(PracticeGroupingGap.MINUTES_30, PracticeGroupingGap.DEFAULT)
    }

    @Test fun overlappingAndBackdatedPlaybacksUseTheLatestEndAndStableStartThenIdOrder() {
        val early = playback(4, -5_000, 50_000)
        val sameTimeLaterId = playback(3, 0, 3_000)
        val sameTimeFirstId = playback(2, 0, 8_000)
        val reachesLongEnd = playback(1, early.endedAt + 300_000)
        val group = project(listOf(sameTimeLaterId, reachesLongEnd, early, sameTimeFirstId), gap = PracticeGroupingGap.MINUTES_5).groups.single()
        assertEquals(listOf(early.id, sameTimeFirstId.id, sameTimeLaterId.id, reachesLongEnd.id), group.playbackIds)
        assertEquals(66_000L, group.playbackMs)
        assertEquals(reachesLongEnd.endedAt - early.startedAt, group.spanMs)
        assertFalse(project(listOf(early), now = Instant.ofEpochMilli(-86_400_000)).groups.single().completed)
    }

    @Test fun manualFinishRequiresAFinalizedRecordingAndClosesAnOpenTail() {
        val p = playback(1, 0)
        val now = Instant.ofEpochMilli(p.endedAt + 1)
        val engine = PracticeGrouping(listOf(p), emptyList(), PracticeGroupingGap.DEFAULT, now)
        val plan = engine.plan(PracticeGroupingChange.FinishAfter(p.id))
        assertFalse(plan.preview.before.single().completed)
        assertTrue(plan.preview.after.single().completed)
        assertNull(engine.summarize().averageCompletedPlaybackMs)
        assertEquals(p.durationMs, project(listOf(p), listOf(plan.addition!!), now = now).averageCompletedPlaybackMs)
        val ongoing = PracticeGrouping(listOf(p.copy(finished = false)), emptyList(), PracticeGroupingGap.DISABLED, now)
        assertThrows(IllegalArgumentException::class.java) { ongoing.plan(PracticeGroupingChange.FinishAfter(p.id)) }
        assertFalse(ongoing.summarize().groups.single().completed)
    }

    @Test fun aLateInsertionKeepsJoinAnchorsAndTheLatestExplicitBoundaryWins() {
        val a = playback(1, 0)
        val late = playback(2, 600_000)
        val b = playback(3, 1_200_000)
        val now = Instant.ofEpochMilli(1_200_001)
        val gap = PracticeGroupingGap.MINUTES_5
        val earlier = PracticeGrouping(listOf(a, b), emptyList(), gap, now)
        val finish = earlier.plan(PracticeGroupingChange.FinishAfter(a.id)).addition!!
        val join = PracticeGrouping(listOf(a, b), listOf(finish), gap, now)
            .plan(PracticeGroupingChange.Join(a.id, b.id)).addition!!
        val withLate = listOf(a, b, late)
        assertEquals(listOf(a.id, late.id, b.id), project(withLate, listOf(join, finish), gap, now).groups.single().playbackIds)
        val newer = PracticeGrouping(withLate, listOf(join, finish), gap, now.minusSeconds(3_600))
            .plan(PracticeGroupingChange.SplitBefore(b.id)).addition!!
        assertEquals(listOf(listOf(a.id, late.id), listOf(b.id)), project(withLate, listOf(newer, finish, join), gap, now).groups.map { it.playbackIds })
        assertEquals(listOf(listOf(a.id), listOf(late.id)), project(listOf(a, late), listOf(join), gap, now).groups.map { it.playbackIds })
    }

    @Test fun manualJoinRetainsBothGroupsWhenThresholdChangesOrIsDisabled() {
        val a = playback(1, 0)
        val b = playback(2, 600_000)
        val c = playback(3, 2_600_000)
        val d = playback(4, 3_200_000)
        val late = playback(5, 300_000)
        val original = listOf(a, b, c, d)
        val engine = PracticeGrouping(original, emptyList(), PracticeGroupingGap.DEFAULT, Instant.EPOCH)
        assertEquals(listOf(listOf(a.id, b.id), listOf(c.id, d.id)), engine.summarize().groups.map { it.playbackIds })
        val join = engine.plan(PracticeGroupingChange.Join(b.id, c.id)).addition!!
        val expected = listOf(listOf(a.id, late.id, b.id, c.id, d.id))
        for (gap in listOf(PracticeGroupingGap.MINUTES_5, PracticeGroupingGap.DISABLED)) {
            assertEquals(expected, project(original + late, listOf(join), gap).groups.map { it.playbackIds })
        }
        assertEquals(a.id, join.playbackId)
        assertEquals(d.id, join.otherPlaybackId)
    }

    @Test fun finishIsIdempotentUntilANewerJoinOverridesItsBoundary() {
        val a = playback(1, 0)
        val b = playback(2, 600_000)
        val now = Instant.ofEpochMilli(b.endedAt)
        val change = PracticeGroupingChange.FinishAfter(a.id)
        val original = PracticeGrouping(listOf(a, b), emptyList(), PracticeGroupingGap.DEFAULT, now)
        val finish = original.plan(change).addition!!
        val finished = PracticeGrouping(listOf(a, b), listOf(finish), PracticeGroupingGap.DEFAULT, now)
        val repeat = finished.plan(change)
        assertNull(repeat.addition)
        assertTrue(repeat.preview.before.isEmpty())
        assertTrue(repeat.preview.after.isEmpty())
        val join = finished.plan(PracticeGroupingChange.Join(a.id, b.id)).addition!!
        val joined = PracticeGrouping(listOf(a, b), listOf(finish, join), PracticeGroupingGap.DEFAULT, now)
        assertEquals(1, joined.summarize().groups.size)
        val finishingAgain = joined.plan(change)
        assertEquals(join.sequenceNumber + 1, finishingAgain.addition!!.sequenceNumber)
        assertEquals(listOf(listOf(a.id), listOf(b.id)), finishingAgain.preview.after.map { it.playbackIds })
    }

    @Test fun resetPreviewShowsChangesOutsideTheSelectedGroupWhenRemovingAWideJoin() {
        val (a, b, c, d, e) = List(5) { playback(it + 1, it * 600_000L) }
        val unrelated = playback(6, 3_000_000)
        val rules = listOf(correction(1, GroupingCorrectionKind.JOIN, a, e),
            correction(2, GroupingCorrectionKind.SPLIT_BEFORE, b), correction(3, GroupingCorrectionKind.SPLIT_BEFORE, d))
        val engine = PracticeGrouping(listOf(a, b, c, d, e, unrelated), rules, PracticeGroupingGap.DISABLED, Instant.EPOCH)
        val initial = engine.summarize().groups.map { it.playbackIds }
        assertEquals(listOf(listOf(a.id), listOf(b.id, c.id), listOf(d.id, e.id), listOf(unrelated.id)), initial)
        val reset = engine.plan(PracticeGroupingChange.ResetGroup(b.id)).preview
        assertEquals(setOf(listOf(b.id, c.id), listOf(d.id, e.id)), reset.before.map { it.playbackIds }.toSet())
        assertEquals(listOf(b.id, c.id, d.id, e.id).map(::listOf), reset.after.map { it.playbackIds })
        assertEquals(rules.map { it.id }.toSet(), reset.correctionsToReset)
    }

    @Test fun resetsRemoveAllDecisionsTouchingTheSelectedGroupAndKeepOtherDecisions() {
        val p = List(4) { playback(it + 1, it * 2_000_000L) }
        val now = Instant.ofEpochMilli(6_005_000)
        val firstJoin = correction(1, GroupingCorrectionKind.JOIN, p[0], p[1])
        val hidden = correction(2, GroupingCorrectionKind.FINISH_AFTER, p[1])
        val split = correction(3, GroupingCorrectionKind.SPLIT_BEFORE, p[2])
        val end = correction(4, GroupingCorrectionKind.FINISH_AFTER, p[3])
        val changes = listOf(firstJoin, hidden, split, end)
        val engine = PracticeGrouping(p, changes, PracticeGroupingGap.DEFAULT, now)
        val local = engine.plan(PracticeGroupingChange.ResetGroup(p[0].id)).preview
        assertEquals(setOf(firstJoin.id, hidden.id, split.id), local.correctionsToReset)
        assertEquals(listOf(p[0].id, p[1].id), local.after.map { it.playbackIds.single() })
        val global = engine.plan(PracticeGroupingChange.ResetAll).preview
        assertEquals(4, global.resetCount)
        assertThrows(IllegalArgumentException::class.java) { engine.plan(PracticeGroupingChange.Join(p[0].id, p[3].id)) }
    }

    @Test fun averageUsesClosedGroupsAndDatesTheirStartsWhileRulesPreserveTotalRecordedTime() {
        val a = playback(1, 0)
        val b = playback(2, 65_000, 10_000)
        val now = Instant.ofEpochMilli(75_000)
        val gap = PracticeGroupingGap.DEFAULT
        val before = project(listOf(a, b), gap = gap, now = now)
        assertEquals(75_000L, before.groups.single().spanMs)
        assertNull(before.averageCompletedPlaybackMs)
        val split = correction(1, GroupingCorrectionKind.SPLIT_BEFORE, b)
        val after = project(listOf(a, b), listOf(split), gap, now)
        assertEquals(5_000L, after.averageCompletedPlaybackMs)
        assertNull(after.averageCompletedPlaybackMsForStarts(1, 200_000))
        assertEquals(before.groups.sumOf { it.playbackMs }, after.groups.sumOf { it.playbackMs })
        assertFalse(project(listOf(a), gap = gap, now = Instant.ofEpochMilli(a.endedAt + 1_800_000)).groups.single().completed)
        assertTrue(project(listOf(a), gap = gap, now = Instant.ofEpochMilli(a.endedAt + 1_800_001)).groups.single().completed)
    }

    @Test(timeout = 10_000) fun largeHistoryWithOverlappingJoinRangesHasBoundedWork() {
        val history = List(55_000) { playback(it + 1, it * 180_000L) }
        val rules = List(12_000) { i -> correction(i + 1, GroupingCorrectionKind.JOIN, history[i], history.last()) }
        val group = project(history, rules).groups.single()
        assertEquals(55_000, group.playbackIds.size)
        assertEquals(275_000_000L, group.playbackMs)
    }

    private fun project(playbacks: List<GroupingPlayback>, rules: List<GroupingCorrection> = emptyList(),
        gap: PracticeGroupingGap = PracticeGroupingGap.DEFAULT, now: Instant = Instant.ofEpochMilli(100_000_000)) =
        PracticeGrouping(playbacks, rules, gap, now).summarize()

    private fun playback(id: Int, start: Long, duration: Long = 5_000) = GroupingPlayback(UUID(0, id.toLong()).toString(), start, duration, true)
    private fun correction(sequence: Int, kind: GroupingCorrectionKind, first: GroupingPlayback, second: GroupingPlayback? = null) =
        GroupingCorrection(UUID(1, sequence.toLong()).toString(), sequence.toLong(), kind, first.id, second?.id)
}
