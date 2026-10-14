package com.leekleak.trafficlight.database

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Interface discovery: when it runs, on which thread, and what happens when the
 * NetworkCallback that normally triggers it could not be registered. Unlike the handover test
 * in [TrafficSnapshotTest], these drive connectivity changes through the captured callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrafficSnapshotInterfaceDiscoveryTest {

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var appPreferenceRepo: AppPreferenceRepo

    private var networks: List<Network> = emptyList()
    private var discoveryFails = false
    private val discoveries = AtomicInteger()
    private val discoveryThreads = CopyOnWriteArrayList<Thread>()
    private val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
    private val rx = mutableMapOf<String, Long>()
    private val tx = mutableMapOf<String, Long>()

    @Before
    fun setUp() {
        mockkStatic(TrafficStats::class)
        every { TrafficStats.getTotalTxBytes() } returns 0L
        every { TrafficStats.getTotalRxBytes() } returns 0L
        every { TrafficStats.getRxBytes(any<String>()) } answers { rx[firstArg()] ?: 0L }
        every { TrafficStats.getTxBytes(any<String>()) } answers { tx[firstArg()] ?: 0L }

        connectivityManager = mockk()
        every { connectivityManager.allNetworks } answers {
            discoveries.incrementAndGet()
            discoveryThreads += Thread.currentThread()
            if (discoveryFails) throw SecurityException("denied")
            networks.toTypedArray()
        }
        every {
            connectivityManager.registerNetworkCallback(any(), capture(callbackSlot))
        } just Runs
        every { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
        appPreferenceRepo = mockk(relaxed = true)
        every { appPreferenceRepo.forceFallback } returns flowOf(false)
    }

    @After
    fun tearDown() {
        unmockkStatic(TrafficStats::class)
    }

    private fun networkWith(interfaceName: String, vararg transports: Int): Network {
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        val transportSet = transports.toSet()
        every { capabilities.hasTransport(any()) } answers { transportSet.contains(firstArg<Int>()) }
        val linkProperties = mockk<LinkProperties>()
        every { linkProperties.interfaceName } returns interfaceName
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { connectivityManager.getLinkProperties(network) } returns linkProperties
        return network
    }

    private fun TestScope.newSnapshot() = TrafficSnapshot(this, appPreferenceRepo, connectivityManager)

    private suspend fun TrafficSnapshot.tick(afterMs: Long = 1_000) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(afterMs))
        updateSnapshot()
        // (setCurrentAsLast is called by the caller where a new baseline is wanted)
    }

    private fun registrationDenied() {
        every {
            connectivityManager.registerNetworkCallback(any(), any<ConnectivityManager.NetworkCallback>())
        } throws SecurityException("denied")
    }

    @Test
    fun `construction does no discovery and the first sample discovers off the calling thread`() = runTest {
        val caller = Thread.currentThread()
        networks = listOf(networkWith("wlan0", NetworkCapabilities.TRANSPORT_WIFI))

        val snapshot = newSnapshot()
        assertEquals("constructing must not query ConnectivityManager", 0, discoveries.get())

        snapshot.updateSnapshot()

        assertEquals(1, discoveries.get())
        discoveryThreads.forEach { assertNotSame(caller, it) }
        assertTrue(discoveryThreads.all { it.name.startsWith("DefaultDispatcher-worker") })
    }

    @Test
    fun `ticks without connectivity events do not re-discover`() = runTest {
        networks = listOf(networkWith("wlan0", NetworkCapabilities.TRANSPORT_WIFI))
        val snapshot = newSnapshot()

        repeat(6) {
            snapshot.tick()
            snapshot.setCurrentAsLast()
        }

        assertEquals(1, discoveries.get())
    }

    @Test
    fun `a burst of callbacks costs one discovery at the next tick`() = runTest {
        val wifiNet = networkWith("wlan0", NetworkCapabilities.TRANSPORT_WIFI)
        networks = listOf(wifiNet)
        val snapshot = newSnapshot()
        snapshot.tick()
        assertEquals(1, discoveries.get())

        val callback = callbackSlot.captured
        callback.onAvailable(wifiNet)
        callback.onCapabilitiesChanged(wifiNet, mockk<NetworkCapabilities>())
        callback.onLinkPropertiesChanged(wifiNet, mockk<LinkProperties>())
        callback.onCapabilitiesChanged(wifiNet, mockk<NetworkCapabilities>())  // e.g. signal strength
        assertEquals("callbacks themselves must not do Binder work", 1, discoveries.get())

        snapshot.tick()
        assertEquals(2, discoveries.get())
        snapshot.tick()
        assertEquals("no further events, no further discovery", 2, discoveries.get())
    }

    @Test
    fun `handover reported by callback rebaselines and then measures the new interface`() = runTest {
        val wifiNet = networkWith("wlan0", NetworkCapabilities.TRANSPORT_WIFI)
        val cellNet = networkWith("rmnet0", NetworkCapabilities.TRANSPORT_CELLULAR)
        rx["wlan0"] = 10_000_000L
        rx["rmnet0"] = 50_000_000_000L
        networks = listOf(wifiNet)
        val snapshot = newSnapshot()
        snapshot.tick()
        snapshot.setCurrentAsLast()

        networks = listOf(cellNet)
        callbackSlot.captured.onAvailable(cellNet)
        snapshot.tick()
        assertEquals("handover must not spike", 0L, snapshot.downSpeed)
        snapshot.setCurrentAsLast()

        rx["rmnet0"] = 50_000_003_000L
        snapshot.tick()
        assertEquals("new interface must be measured, not stuck on the old cache", 3_000L, snapshot.downSpeed)
    }

    @Test
    fun `a VPN network appearing via callback is still excluded`() = runTest {
        val wifiNet = networkWith("wlan0", NetworkCapabilities.TRANSPORT_WIFI)
        val vpnNet = networkWith("tun0", NetworkCapabilities.TRANSPORT_VPN)
        rx["wlan0"] = 1_000_000L; tx["wlan0"] = 500_000L
        rx["tun0"] = 900_000L; tx["tun0"] = 450_000L
        networks = listOf(wifiNet)
        val snapshot = newSnapshot()
        snapshot.tick()
        snapshot.setCurrentAsLast()

        networks = listOf(wifiNet, vpnNet)
        callbackSlot.captured.onAvailable(vpnNet)
        rx["wlan0"] = 1_200_000L; tx["wlan0"] = 600_000L
        rx["tun0"] = 1_140_000L; tx["tun0"] = 570_000L
        snapshot.tick()

        assertEquals(200_000L, snapshot.downSpeed)
        assertEquals(100_000L, snapshot.upSpeed)
    }

    @Test
    fun `when callback registration is denied discovery is polled at a throttled rate`() = runTest {
        registrationDenied()
        networks = listOf(networkWith("wlan0", NetworkCapabilities.TRANSPORT_WIFI))
        val snapshot = newSnapshot()

        snapshot.tick(afterMs = 0)
        assertEquals(1, discoveries.get())
        snapshot.tick(afterMs = 1_000)
        assertEquals("within the polling interval: no extra Binder queries", 1, discoveries.get())
        snapshot.tick(afterMs = 1_500)   // 2.5s since the first discovery
        assertEquals(2, discoveries.get())
        snapshot.tick(afterMs = 1_000)
        assertEquals(2, discoveries.get())
    }

    @Test
    fun `when callback registration is denied a handover is still picked up`() = runTest {
        registrationDenied()
        val wifiNet = networkWith("wlan0", NetworkCapabilities.TRANSPORT_WIFI)
        val cellNet = networkWith("rmnet0", NetworkCapabilities.TRANSPORT_CELLULAR)
        rx["wlan0"] = 10_000_000L
        rx["rmnet0"] = 50_000_000_000L
        networks = listOf(wifiNet)
        val snapshot = newSnapshot()
        snapshot.tick(afterMs = 0)
        snapshot.setCurrentAsLast()

        networks = listOf(cellNet)       // no callback will ever announce this
        snapshot.tick(afterMs = TrafficSnapshot.POLLING_REFRESH_INTERVAL_MS)
        assertEquals(0L, snapshot.downSpeed)
        snapshot.setCurrentAsLast()

        rx["rmnet0"] = 50_000_004_000L
        snapshot.tick(afterMs = 1_000)
        assertEquals(4_000L, snapshot.downSpeed)
    }

    @Test
    fun `stopping tracking unregisters once and falls back to polling`() = runTest {
        val wifiNet = networkWith("wlan0", NetworkCapabilities.TRANSPORT_WIFI)
        val cellNet = networkWith("rmnet0", NetworkCapabilities.TRANSPORT_CELLULAR)
        networks = listOf(wifiNet)
        val snapshot = newSnapshot()
        snapshot.tick()

        snapshot.stopTrackingNetworkChanges()
        snapshot.stopTrackingNetworkChanges()
        verify(exactly = 1) { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }

        networks = listOf(cellNet)
        snapshot.tick(afterMs = TrafficSnapshot.POLLING_REFRESH_INTERVAL_MS)
        assertEquals("a cache nothing can invalidate must be re-polled", 2, discoveries.get())
    }

    @Test
    fun `a denied discovery keeps the previous interfaces instead of failing`() = runTest {
        val wifiNet = networkWith("wlan0", NetworkCapabilities.TRANSPORT_WIFI)
        rx["wlan0"] = 1_000L
        networks = listOf(wifiNet)
        val snapshot = newSnapshot()
        snapshot.tick()
        snapshot.setCurrentAsLast()

        discoveryFails = true
        callbackSlot.captured.onLost(wifiNet)
        rx["wlan0"] = 3_000L
        snapshot.tick()    // discovery throws SecurityException internally; must not propagate

        assertEquals(2_000L, snapshot.downSpeed)
    }
}
