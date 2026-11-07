package org.openmetronome.ui

import android.content.*
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

/** Fake clock and receiver registry: never changes device time/timezone or sends protected broadcasts. */
class PracticeDateTest {
    @Test fun dateRefreshesAtClockChangesAndReceiverIsRemovedWhenScreenLeaves() = runBlocking<Unit> {
        val receiver = AtomicReference<BroadcastReceiver>()
        var removed = false
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun registerReceiver(value: BroadcastReceiver?, filter: IntentFilter?): Intent? {
                assertTrue(filter!!.hasAction(Intent.ACTION_TIMEZONE_CHANGED))
                assertTrue(filter.hasAction(Intent.ACTION_TIME_CHANGED))
                receiver.set(value); return null
            }
            override fun registerReceiver(value: BroadcastReceiver?, filter: IntentFilter?, flags: Int): Intent? = registerReceiver(value, filter)
            override fun unregisterReceiver(value: BroadcastReceiver?) { assertSame(receiver.get(), value); removed = true }
        }
        val clock = AtomicReference(ZonedDateTime.of(2026, 11, 6, 12, 0, 0, 0, ZoneId.of("Europe/Warsaw")))
        val values = Channel<java.time.LocalDate>(Channel.UNLIMITED)
        val collection = launch(Dispatchers.Default) { observePracticeDate(context) { clock.get() }.collect { values.send(it) } }
        suspend fun next() = withTimeout(5000) { values.receive() }
        try {
            assertEquals(java.time.LocalDate.of(2026, 11, 6), next())
            clock.set(clock.get().plusHours(12))
            receiver.get().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
            assertEquals(java.time.LocalDate.of(2026, 11, 7), next())
            clock.set(clock.get().withZoneSameInstant(ZoneId.of("Pacific/Honolulu")))
            receiver.get().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
            assertEquals(java.time.LocalDate.of(2026, 11, 6), next())
        } finally { collection.cancelAndJoin(); values.close() }
        assertTrue(removed)
    }

    @Test fun cancellingObserverWhileClockCallbackRunsDoesNotCloseItsPendingReceive() = runBlocking<Unit> {
        val receiver = AtomicReference<BroadcastReceiver>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val removed = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun registerReceiver(value: BroadcastReceiver?, filter: IntentFilter?): Intent? { receiver.set(value); return null }
            override fun registerReceiver(value: BroadcastReceiver?, filter: IntentFilter?, flags: Int): Intent? = registerReceiver(value, filter)
            override fun unregisterReceiver(value: BroadcastReceiver?) { assertSame(receiver.get(), value); removed.countDown() }
        }
        val first = CompletableDeferred<Unit>()
        val collection = launch(Dispatchers.Default) {
            observePracticeDate(context) {
                if (calls.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                ZonedDateTime.of(2026, 11, 7, 12, 0, 0, 0, ZoneId.of("UTC"))
            }.collect { first.complete(Unit) }
        }
        try {
            withTimeout(5000) { first.await() }
            receiver.get().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            collection.cancel()
            assertTrue(removed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            collection.join()
        } finally { release.countDown(); collection.cancelAndJoin() }
        assertEquals(0, removed.count)
    }
}
