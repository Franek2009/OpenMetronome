package org.openmetronome.diagnostics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.lang.management.ManagementFactory
import com.sun.management.ThreadMXBean
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.audio.PcmTraceKind
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.Tempo

class BoundedPcmTraceTest {
    @Test fun capacityIsBoundedAndInvalidSizesFailBeforeAllocating() {
        for (size in listOf(0, -1, 3, BoundedPcmTrace.MAX_CAPACITY * 2)) {
            assertThrows(IllegalArgumentException::class.java) { BoundedPcmTrace(size) }
        }
        val trace = BoundedPcmTrace()
        assertTrue(trace.reservedBytes < BoundedPcmTrace.BUFFER_BUDGET_BYTES)
        assertNull(trace.poll())
    }

    @Test fun fullRingPreservesUnreadRecordsAndExposesSequenceGapsAfterDrain() {
        var nanos = 40L
        val trace = BoundedPcmTrace(2) { nanos++ }
        trace.record(PcmTraceKind.BEAT, 100)
        trace.record(PcmTraceKind.BEAT, 200)
        trace.record(PcmTraceKind.BEAT, 300) // lost, not an overwrite
        assertEquals(0L, trace.poll()!!.sequence)
        trace.record(PcmTraceKind.BEAT, 400)
        val second = trace.poll()!!
        val fourth = trace.poll()!!
        assertEquals(1L, second.sequence)
        assertEquals(200L, second.frame)
        assertEquals(3L, fourth.sequence)
        assertEquals(400L, fourth.frame)
        assertEquals(43L, fourth.nanos)
        assertNull(trace.poll())
        val health = trace.health()!!
        assertEquals(4L, health.attempted)
        assertEquals(3L, health.accepted)
        assertEquals(1L, health.dropped)
        assertEquals(1L, health.droppedByKind.getValue(PcmTraceKind.BEAT))
        assertFalse(health.faulted)
    }

    @Test fun consumedValuesAndConfigsDoNotAliasReusedSlots() {
        val firstConfig = MetronomeConfig(tempo = Tempo(91))
        val secondConfig = MetronomeConfig(tempo = Tempo(137))
        val trace = BoundedPcmTrace(1)
        trace.record(PcmTraceKind.CONFIG_OBSERVED, 11, 1, 2, 3, 4, firstConfig)
        val first = trace.poll()!!
        trace.record(PcmTraceKind.CONFIG_APPLIED, 22, 5, 6, 7, 8, secondConfig)
        val second = trace.poll()!!
        assertEquals(firstConfig, first.config)
        assertEquals(listOf(11L, 1L, 2L, 3L, 4L), listOf(first.frame, first.a, first.b, first.c, first.d))
        assertEquals(secondConfig, second.config)
        assertEquals(listOf(22L, 5L, 6L, 7L, 8L), listOf(second.frame, second.a, second.b, second.c, second.d))
    }

    @Test fun wrappingRepeatedlyDoesNotRetainOldConfigReferences() {
        val trace = BoundedPcmTrace(2)
        repeat(1000) { index ->
            val config = if (index % 2 == 0) MetronomeConfig(tempo = Tempo(91)) else null
            trace.record(PcmTraceKind.CONFIG_OBSERVED, index.toLong(), config = config)
            val record = trace.poll()!!
            assertEquals(index.toLong(), record.sequence)
            assertEquals(config, record.config)
        }
        assertEquals(0L, trace.health()!!.dropped)
    }

    @Test fun configurationReferencesHaveTheirOwnBoundWithoutSuppressingBeatRecords() {
        val trace = BoundedPcmTrace(1024)
        repeat(trace.maxConfigRecords) { index ->
            trace.record(PcmTraceKind.CONFIG_OBSERVED, index.toLong(), config = MetronomeConfig())
        }
        trace.record(PcmTraceKind.CONFIG_OBSERVED, 999, config = MetronomeConfig())
        trace.record(PcmTraceKind.BEAT, 1000)
        assertEquals(1L, trace.health()!!.dropped)
        assertEquals(1L, trace.health()!!.droppedByKind.getValue(PcmTraceKind.CONFIG_OBSERVED))
        assertEquals(0L, trace.health()!!.droppedByKind.getValue(PcmTraceKind.BEAT))
        assertNotNull(trace.poll()!!.config)
        trace.record(PcmTraceKind.CONFIG_OBSERVED, 1001, config = MetronomeConfig())
        assertEquals(1L, trace.health()!!.dropped)
        val rows = generateSequence { trace.poll() }.toList()
        assertTrue(rows.any { it.kind == PcmTraceKind.BEAT && it.frame == 1000L })
        assertEquals(1001L, rows.last().frame)
    }

    @Test fun consumerStallCannotBlockTheProducer() {
        val trace = BoundedPcmTrace(1)
        val finished = CountDownLatch(1)
        val producer = Thread {
            repeat(100_000) { trace.record(PcmTraceKind.WRITE_BEGIN, it.toLong()) }
            trace.close()
            finished.countDown()
        }
        producer.start()
        assertTrue("Producer waited for a nonexistent consumer", finished.await(10, TimeUnit.SECONDS))
        producer.join(1000)
        val health = trace.health()!!
        assertEquals(100_000L, health.attempted)
        assertEquals(1L, health.accepted)
        assertEquals(99_999L, health.dropped)
        assertTrue(health.closed)
    }

    @Test fun producerDoesNotAllocatePerRecordAfterWarmupOnTheJvm() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val warmup = BoundedPcmTrace(1)
        repeat(50_000) {
            warmup.record(PcmTraceKind.BEAT, it.toLong())
            warmup.poll()
        }
        val trace = BoundedPcmTrace()
        @Suppress("DEPRECATION") val thread = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(20_000) { trace.record(PcmTraceKind.BEAT, it.toLong()) }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        println("PCM_TRACE_ALLOCATION records=20000 producer_bytes=$allocated")
        assertEquals(20_000L, trace.health()!!.accepted)
        assertTrue("Producer allocated $allocated bytes for 20000 records", allocated <= 4096)
        // JVM evidence only: Android ART and the complete future recorder need
        // separate measurements before activating recording in the DEV app.
    }

    @Test fun concurrentConsumerNeverSeesTornOrReorderedRecords() {
        val trace = BoundedPcmTrace(16) { 99L }
        val done = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val records = ArrayList<PcmTraceRecord>()
        val consumer = Thread {
            try {
                while (true) {
                    trace.health()?.let {
                        check(it.attempted == it.accepted + it.dropped)
                        check(it.dropped == it.droppedByKind.values.sum())
                    }
                    val row = trace.poll()
                    if (row != null) records.add(row)
                    else if (done.get()) {
                        val last = trace.poll() ?: break
                        records.add(last)
                    }
                    else Thread.yield()
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        consumer.start()
        repeat(20_000) { value ->
            val x = value.toLong()
            trace.record(PcmTraceKind.WRITE_RESULT, x, x * 2, x * 3, x * 4, x * 5)
        }
        trace.close()
        done.set(true)
        consumer.join(10_000)
        assertFalse("Consumer did not terminate", consumer.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        var previous = -1L
        records.forEach { row ->
            assertTrue(row.sequence > previous)
            previous = row.sequence
            assertEquals(99L, row.nanos)
            assertEquals(row.sequence, row.frame)
            assertEquals(listOf(row.frame * 2, row.frame * 3, row.frame * 4, row.frame * 5),
                listOf(row.a, row.b, row.c, row.d))
        }
        val health = trace.health()!!
        assertEquals(20_000L, health.attempted)
        assertEquals(records.size.toLong(), health.accepted)
        assertEquals(health.attempted, health.accepted + health.dropped)
    }
}
