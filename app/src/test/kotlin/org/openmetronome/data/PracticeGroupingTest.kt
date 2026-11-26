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
