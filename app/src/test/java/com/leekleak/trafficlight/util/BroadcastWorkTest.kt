package com.leekleak.trafficlight.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** `PendingResult.finish()` must be called exactly once no matter how the work ends. */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastWorkTest {

    @Test
    fun `finish is called once after the work completes`() = runTest {
        val events = mutableListOf<String>()
        val job = launchBroadcastWork(finish = { events += "finish" }) { events += "work" }
        job.join()
        assertEquals(listOf("work", "finish"), events)
    }

    @Test
    fun `finish is still called when the work throws, and the failure does not escape`() = runTest {
        val finished = AtomicInteger()
        val job = launchBroadcastWork(finish = { finished.incrementAndGet() }) {
            throw IllegalStateException("widget update exploded")
        }
        job.join()
        assertEquals(1, finished.get())
        assertFalse("failure must be contained, not cancel the app-wide scope", job.isCancelled)
    }

    @Test
    fun `finish is called when the work is cancelled`() = runTest {
        val finished = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val job = launchBroadcastWork(finish = { finished.incrementAndGet() }) {
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(1, finished.get())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `work that overruns the budget is cancelled and finish is called`() = runTest {
        val finished = AtomicInteger()
        var cancelledInside = false
        val job = launchBroadcastWork(finish = { finished.incrementAndGet() }, budget = 8.seconds) {
            try {
                delay(60.seconds)
            } finally {
                cancelledInside = true
            }
        }
        advanceTimeBy(7_999)
        runCurrent()
        assertEquals("must not finish before the budget is spent", 0, finished.get())

        advanceTimeBy(2)
        runCurrent()
        job.join()
        assertEquals(1, finished.get())
        assertTrue("overrunning work must be cancelled", cancelledInside)
    }

    @Test
    fun `work finishing inside the budget completes normally`() = runTest {
        val finished = AtomicInteger()
        var done = false
        val job = launchBroadcastWork(finish = { finished.incrementAndGet() }, budget = 8.seconds) {
            delay(7.seconds)
            done = true
        }
        advanceTimeBy(7_001)
        runCurrent()
        job.join()
        assertTrue(done)
        assertEquals(1, finished.get())
    }
}
