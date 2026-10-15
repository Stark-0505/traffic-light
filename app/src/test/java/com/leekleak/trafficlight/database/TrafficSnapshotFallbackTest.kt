package com.leekleak.trafficlight.database

import android.net.ConnectivityManager
import android.net.TrafficStats
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives [TrafficSnapshot] in sysfs-fallback mode through its public API with a
 * [FakeSysfsReader], so no real `/sys` files are needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrafficSnapshotFallbackTest {

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var appPreferenceRepo: AppPreferenceRepo

    private val wifiRx = SysfsFallbackCounters.WIFI_RX
    private val wifiTx = SysfsFallbackCounters.WIFI_TX
    private val mobileRx = SysfsFallbackCounters.MOBILE_RX
    private val mobileTx = SysfsFallbackCounters.MOBILE_TX

    @Before
    fun setUp() {
        mockkStatic(TrafficStats::class)
        every { TrafficStats.getTotalTxBytes() } returns 0L
        every { TrafficStats.getTotalRxBytes() } returns 0L

        connectivityManager = mockk()
        every { connectivityManager.allNetworks } returns arrayOf()
        every { connectivityManager.registerNetworkCallback(any(), any<ConnectivityManager.NetworkCallback>()) } just Runs
        every { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
        appPreferenceRepo = mockk(relaxed = true)
        every { appPreferenceRepo.forceFallback } returns flowOf(true)
    }

    @After
    fun tearDown() {
        unmockkStatic(TrafficStats::class)
    }

    private fun TestScope.fallbackSnapshot(
        reader: FakeSysfsReader,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): TrafficSnapshot {
        val snapshot = TrafficSnapshot(this, appPreferenceRepo, connectivityManager, SysfsFallbackCounters(reader), dispatcher)
        // Let the forceFallback collector from TrafficSnapshot's init block run so the
        // snapshot is deterministically in fallback mode before the first sample.
        runCurrent()
        return snapshot
    }

    @Test
    fun `fallback sysfs reads never run on the calling thread`() = runTest {
        val callerThread = Thread.currentThread()
        val reader = FakeSysfsReader(mapOf(wifiRx to "100", wifiTx to "10"))
        val snapshot = fallbackSnapshot(reader)

        snapshot.updateSnapshot()

        assertTrue("expected at least one sysfs read", reader.readThreads.isNotEmpty())
        reader.readThreads.forEach { assertNotSame(callerThread, it) }
        assertTrue(reader.readThreads.all { it.name.startsWith("DefaultDispatcher-worker") })
        assertTrue(reader.probeThreads.all { it.name.startsWith("DefaultDispatcher-worker") })
    }

    @Test
    fun `fallback computes independent upload and download speed from counter deltas`() = runTest {
        val reader = FakeSysfsReader(mapOf(wifiRx to "1000", wifiTx to "5000"))
        val snapshot = fallbackSnapshot(reader)
        snapshot.updateSnapshot()
        snapshot.setCurrentAsLast()

        reader.files[wifiRx] = "1500"   // +500 down
        reader.files[wifiTx] = "9000"   // +4000 up
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        snapshot.updateSnapshot()

        assertEquals(250L, snapshot.downSpeed)
        assertEquals(2_000L, snapshot.upSpeed)
        assertFalse(snapshot.isCurrentSameAsLast())
    }

    @Test
    fun `upload can rise while download stays flat`() = runTest {
        val reader = FakeSysfsReader(mapOf(wifiRx to "1000", wifiTx to "1000"))
        val snapshot = fallbackSnapshot(reader)
        snapshot.updateSnapshot()
        snapshot.setCurrentAsLast()

        reader.files[wifiTx] = "3000"
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        snapshot.updateSnapshot()

        assertEquals(0L, snapshot.downSpeed)
        assertEquals(2_000L, snapshot.upSpeed)
    }

    @Test
    fun `counter regression reports zero instead of a negative or overflowed speed`() = runTest {
        val reader = FakeSysfsReader(mapOf(wifiRx to "9000000", wifiTx to "9000000"))
        val snapshot = fallbackSnapshot(reader)
        snapshot.updateSnapshot()
        snapshot.setCurrentAsLast()

        // Interface counters reset (e.g. driver reload).
        reader.files[wifiRx] = "10"
        reader.files[wifiTx] = "10"
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        snapshot.updateSnapshot()

        assertEquals(0L, snapshot.downSpeed)
        assertEquals(0L, snapshot.upSpeed)
    }

    @Test
    fun `no readable interfaces keeps fallback enabled and reports zero`() = runTest {
        val reader = FakeSysfsReader()
        val snapshot = fallbackSnapshot(reader)

        snapshot.updateSnapshot()
        snapshot.setCurrentAsLast()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        snapshot.updateSnapshot()
        runCurrent()

        assertEquals(0L, snapshot.totalSpeed)
        coVerify(exactly = 0) { appPreferenceRepo.setForceFallback(false) }
    }

    @Test
    fun `unreadable interface file is skipped without disabling fallback`() = runTest {
        val reader = FakeSysfsReader(mapOf(wifiRx to "100", wifiTx to "10", mobileRx to "5", mobileTx to "5"))
        reader.unreadable += setOf(mobileRx, mobileTx)
        val snapshot = fallbackSnapshot(reader)
        snapshot.updateSnapshot()
        snapshot.setCurrentAsLast()

        reader.files[wifiRx] = "200"
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        snapshot.updateSnapshot()
        runCurrent()

        assertEquals(100L, snapshot.downSpeed)
        coVerify(exactly = 0) { appPreferenceRepo.setForceFallback(false) }
    }

    @Test
    fun `malformed counter disables the persisted fallback preference`() = runTest {
        val reader = FakeSysfsReader(mapOf(wifiRx to "garbage", wifiTx to "1"))
        val snapshot = fallbackSnapshot(reader)

        snapshot.updateSnapshot()   // must not throw
        runCurrent()

        coVerify(exactly = 1) { appPreferenceRepo.setForceFallback(false) }
    }

    @Test
    fun `io failure disables the persisted fallback preference`() = runTest {
        val reader = FakeSysfsReader(mapOf(wifiRx to "1", wifiTx to "1"))
        reader.readFailure = IOException("read failed")
        val snapshot = fallbackSnapshot(reader)

        snapshot.updateSnapshot()
        runCurrent()

        coVerify(exactly = 1) { appPreferenceRepo.setForceFallback(false) }
    }

    @Test
    fun `selinux denial disables the persisted fallback preference`() = runTest {
        val reader = FakeSysfsReader(mapOf(wifiRx to "1", wifiTx to "1"))
        reader.readFailure = SecurityException("avc: denied")
        val snapshot = fallbackSnapshot(reader)

        snapshot.updateSnapshot()
        runCurrent()

        coVerify(exactly = 1) { appPreferenceRepo.setForceFallback(false) }
    }

    @Test
    fun `after a fallback failure sampling switches to the regular path`() = runTest {
        val reader = FakeSysfsReader(mapOf(wifiRx to "1", wifiTx to "1"))
        reader.readFailure = IOException("read failed")
        val snapshot = fallbackSnapshot(reader)
        snapshot.updateSnapshot()
        val readsAfterFailure = reader.readThreads.size

        snapshot.updateSnapshot()

        assertEquals("sysfs must not be retried every tick once it failed", readsAfterFailure, reader.readThreads.size)
    }

    @Test
    fun `cancellation is propagated and does not disable the fallback`() = runTest {
        val reader = FakeSysfsReader(mapOf(wifiRx to "1", wifiTx to "1"))
        reader.readFailure = CancellationException("cancelled mid-read")
        val snapshot = fallbackSnapshot(reader)

        var propagated = false
        try {
            snapshot.updateSnapshot()
        } catch (e: CancellationException) {
            propagated = true
        }
        runCurrent()

        assertTrue("CancellationException must not be swallowed", propagated)
        coVerify(exactly = 0) { appPreferenceRepo.setForceFallback(false) }
    }

    @Test
    fun `doesFallbackWork probes off the calling thread`() = runTest {
        val callerThread = Thread.currentThread()
        val reader = FakeSysfsReader(mapOf(wifiRx to "1"))

        val works = TrafficSnapshot.doesFallbackWork(SysfsFallbackCounters(reader), Dispatchers.IO)

        assertTrue(works)
        assertTrue(reader.probeThreads.isNotEmpty())
        reader.probeThreads.forEach { assertNotSame(callerThread, it) }
    }

    @Test
    fun `doesFallbackWork is false when no counter is readable`() = runTest {
        assertFalse(TrafficSnapshot.doesFallbackWork(SysfsFallbackCounters(FakeSysfsReader()), Dispatchers.IO))
    }
}
