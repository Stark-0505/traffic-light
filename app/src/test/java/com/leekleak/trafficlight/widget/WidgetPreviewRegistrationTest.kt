package com.leekleak.trafficlight.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetPreviewRegistrationTest {

    @Test
    fun `registration waits for the delay and then runs off the calling thread`() = runTest {
        val callerThread = Thread.currentThread()
        val registeredOn = CompletableDeferred<Thread>()

        val job = launch {
            registerWidgetPreviews(initialDelay = 1.seconds) { registeredOn.complete(Thread.currentThread()) }
        }

        advanceTimeBy(999)
        runCurrent()
        assertFalse("must not register before the delay has elapsed", registeredOn.isCompleted)

        advanceTimeBy(2)
        runCurrent()
        val thread = registeredOn.await()
        job.join()

        assertNotSame(callerThread, thread)
        assertTrue(thread.name.startsWith("DefaultDispatcher-worker"))
    }

    @Test
    fun `registration is attempted exactly once`() = runTest {
        val calls = AtomicInteger()
        registerWidgetPreviews(initialDelay = 1.seconds, ioDispatcher = Dispatchers.IO) { calls.incrementAndGet() }
        assertEquals(1, calls.get())
    }

    @Test
    fun `cancelling during the delay never registers`() = runTest {
        val calls = AtomicInteger()
        val job = launch {
            registerWidgetPreviews(initialDelay = 1.seconds) { calls.incrementAndGet() }
        }
        advanceTimeBy(500)
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, calls.get())
    }

    @Test
    fun `registration failure is contained instead of crashing the caller`() = runTest {
        // Must return normally; an escaping exception would crash the launching lifecycleScope.
        registerWidgetPreviews(initialDelay = 1.seconds) { throw IllegalStateException("launcher rejected previews") }
    }

    @Test
    fun `cancellation raised inside registration still propagates`() = runTest {
        var propagated = false
        try {
            registerWidgetPreviews(initialDelay = 1.seconds) { throw kotlinx.coroutines.CancellationException("cancelled") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }
}
