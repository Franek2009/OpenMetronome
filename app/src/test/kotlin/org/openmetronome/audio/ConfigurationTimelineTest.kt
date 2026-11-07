package org.openmetronome.audio

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig

class ConfigurationTimelineTest {
    @Test fun boundedQueueNeverConsumesFutureFramesAndReportsDroppedChanges() {
        val timeline = ConfigurationTimeline(2)
        val config = MetronomeConfig()
        timeline.record(0, config); timeline.record(100, config); timeline.record(200, config)
        assertEquals(1L, timeline.dropped)
        assertEquals(listOf(0L), timeline.drainThrough(50).map { it.frame })
        assertTrue(timeline.drainThrough(99).isEmpty())
        timeline.record(300, config)
        assertEquals(listOf(100L, 300L), timeline.drainThrough(300).map { it.frame })
    }

    @Test fun producerAndConsumerPreserveAllAcceptedFramesAcrossSlotReuse() {
        val timeline = ConfigurationTimeline(64)
        val finished = AtomicBoolean(false)
        val config = MetronomeConfig()
        val producer = Thread { repeat(100000) { timeline.record(it.toLong(), config) }; finished.set(true) }
        val frames = mutableListOf<Long>()
        producer.start()
        while (!finished.get()) { frames.addAll(timeline.drainThrough(Long.MAX_VALUE).map { it.frame }); Thread.yield() }
        producer.join(); frames.addAll(timeline.drainThrough(Long.MAX_VALUE).map { it.frame })
        assertEquals(100000L, frames.size + timeline.dropped)
        assertTrue(frames.zipWithNext().all { (a, b) -> a < b })
    }
}
