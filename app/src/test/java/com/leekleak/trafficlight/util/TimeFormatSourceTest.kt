package com.leekleak.trafficlight.util

import android.content.Context
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimeFormatSourceTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val uri = Settings.System.getUriFor(Settings.System.TIME_12_24)

    /** Simulates the user flipping the system setting: change the value, then notify observers. */
    private fun changeSetting(setting: FakeSetting, is24: Boolean) {
        setting.is24 = is24
        context.contentResolver.notifyChange(uri, null)
        shadowOf(Looper.getMainLooper()).idle()   // ContentObserver dispatches via a main-looper Handler
    }

    private class FakeSetting(@Volatile var is24: Boolean) {
        val readThreads = CopyOnWriteArrayList<Thread>()
        val reads = AtomicInteger()
        fun read(): Boolean {
            readThreads += Thread.currentThread()
            reads.incrementAndGet()
            return is24
        }
    }

    @Test
    fun `the preference is resolved off the calling thread`() = runTest {
        val caller = Thread.currentThread()
        val setting = FakeSetting(true)
        val source = TimeFormatSource(context, backgroundScope, Dispatchers.IO, { setting.read() }, initialValue = false)

        val first = source.is24Hour.first { it }   // initial value is false; wait for the resolved one

        assertTrue(first)
        assertTrue(setting.readThreads.isNotEmpty())
        setting.readThreads.forEach { assertNotSame(caller, it) }
        assertTrue(setting.readThreads.all { it.name.startsWith("DefaultDispatcher-worker") })
    }

    @Test
    fun `both modes are reported`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        for (mode in listOf(true, false)) {
            val setting = FakeSetting(mode)
            val source = TimeFormatSource(context, backgroundScope, dispatcher, { setting.read() }, initialValue = !mode)
            val seen = mutableListOf<Boolean>()
            val job = backgroundScope.launch(dispatcher) { source.is24Hour.collect { seen += it } }
            runCurrent()
            assertEquals(mode, seen.last())
            job.cancel()
        }
    }

    @Test
    fun `a system setting change while observing updates the value`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val setting = FakeSetting(false)
        val source = TimeFormatSource(context, backgroundScope, dispatcher, { setting.read() }, initialValue = false)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(dispatcher) { source.is24Hour.collect { seen += it } }
        runCurrent()
        assertEquals(false, seen.last())

        changeSetting(setting, true)
        runCurrent()
        assertEquals(true, seen.last())

        changeSetting(setting, false)
        runCurrent()
        assertEquals(false, seen.last())
    }

    @Test
    fun `rapid changes settle on the final value`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val setting = FakeSetting(false)
        val source = TimeFormatSource(context, backgroundScope, dispatcher, { setting.read() }, initialValue = false)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(dispatcher) { source.is24Hour.collect { seen += it } }
        runCurrent()

        changeSetting(setting, true)
        changeSetting(setting, false)
        changeSetting(setting, true)
        runCurrent()

        assertEquals("last write must win, not an out-of-order earlier read", true, seen.last())
    }

    @Test
    fun `the observer is registered only while collected and is unregistered afterwards`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val setting = FakeSetting(true)
        val source = TimeFormatSource(context, backgroundScope, dispatcher, { setting.read() }, initialValue = true)
        runCurrent()
        assertEquals("no observer before anyone collects", 0, ShadowContentResolver.getContentObservers(uri).size)

        val job: Job = backgroundScope.launch(dispatcher) { source.is24Hour.collect { } }
        runCurrent()
        assertEquals(1, ShadowContentResolver.getContentObservers(uri).size)

        // A second collector shares the single observer.
        val second = backgroundScope.launch(dispatcher) { source.is24Hour.collect { } }
        runCurrent()
        assertEquals(1, ShadowContentResolver.getContentObservers(uri).size)

        job.cancel(); second.cancel()
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals("observer must be unregistered once nothing collects", 0, ShadowContentResolver.getContentObservers(uri).size)
    }

    @Test
    fun `re-subscribing re-reads the setting so a change made while unobserved is not missed`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val setting = FakeSetting(false)
        val source = TimeFormatSource(context, backgroundScope, dispatcher, { setting.read() }, initialValue = false)

        val firstJob = backgroundScope.launch(dispatcher) { source.is24Hour.collect { } }
        runCurrent()
        firstJob.cancel()
        advanceTimeBy(5_001)
        runCurrent()

        setting.is24 = true   // changed while nothing was observing
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(dispatcher) { source.is24Hour.collect { seen += it } }
        runCurrent()

        assertEquals(true, seen.last())
    }
}
