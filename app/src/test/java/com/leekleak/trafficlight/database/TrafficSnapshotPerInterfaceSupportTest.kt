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
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

private const val UNSUPPORTED = -1L

/**
 * Some platform versions do not serve per-interface TrafficStats to apps (upstream only used
 * them on API 31+). When every interface answers UNSUPPORTED, sampling must switch to device
 * totals rather than reading a permanent 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrafficSnapshotPerInterfaceSupportTest {

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var appPreferenceRepo: AppPreferenceRepo
    private val rx = mutableMapOf<String, Long>()
    private val tx = mutableMapOf<String, Long>()
    private var totalRx = 0L
    private var totalTx = 0L
    private var networks: List<Network> = emptyList()

    @Before
    fun setUp() {
        mockkStatic(TrafficStats::class)
        every { TrafficStats.getTotalRxBytes() } answers { totalRx }
        every { TrafficStats.getTotalTxBytes() } answers { totalTx }
        every { TrafficStats.getRxBytes(any<String>()) } answers { rx[firstArg()] ?: UNSUPPORTED }
        every { TrafficStats.getTxBytes(any<String>()) } answers { tx[firstArg()] ?: UNSUPPORTED }
        connectivityManager = mockk()
        every { connectivityManager.allNetworks } answers { networks.toTypedArray() }
        every { connectivityManager.registerNetworkCallback(any(), any<ConnectivityManager.NetworkCallback>()) } just Runs
        every { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
        appPreferenceRepo = mockk(relaxed = true)
        every { appPreferenceRepo.forceFallback } returns flowOf(false)
    }

    @After
    fun tearDown() = unmockkStatic(TrafficStats::class)

    private fun network(iface: String, transport: Int): Network {
        val n = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()
        every { caps.hasTransport(any()) } answers { firstArg<Int>() == transport }
        val lp = mockk<LinkProperties>()
        every { lp.interfaceName } returns iface
        every { connectivityManager.getNetworkCapabilities(n) } returns caps
        every { connectivityManager.getLinkProperties(n) } returns lp
        return n
    }

    private fun TestScope.newSnapshot() = TrafficSnapshot(this, appPreferenceRepo, connectivityManager)

    private suspend fun TrafficSnapshot.tick() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        updateSnapshot()
    }

    @Test
    fun `unsupported per-interface counters use device totals instead of a permanent zero`() = runTest {
        networks = listOf(network("wlan0", NetworkCapabilities.TRANSPORT_WIFI))   // no rx/tx entries -> UNSUPPORTED
        totalRx = 1_000L; totalTx = 100L
        val snapshot = newSnapshot()
        snapshot.tick()
        snapshot.setCurrentAsLast()

        totalRx = 3_000L; totalTx = 400L
        snapshot.tick()

        assertEquals(2_000L, snapshot.downSpeed)
        assertEquals(300L, snapshot.upSpeed)
    }

    @Test
    fun `supported per-interface counters are used and device totals ignored`() = runTest {
        networks = listOf(network("wlan0", NetworkCapabilities.TRANSPORT_WIFI))
        rx["wlan0"] = 1_000L; tx["wlan0"] = 100L
        totalRx = 5_000L; totalTx = 500L
        val snapshot = newSnapshot()
        snapshot.tick()
        snapshot.setCurrentAsLast()

        rx["wlan0"] = 1_500L; tx["wlan0"] = 150L
        totalRx = 9_999L; totalTx = 9_999L     // e.g. inflated by a VPN tunnel: must not matter
        snapshot.tick()

        assertEquals(500L, snapshot.downSpeed)
        assertEquals(50L, snapshot.upSpeed)
    }

    @Test
    fun `one unsupported interface among supported ones stays per-interface`() = runTest {
        networks = listOf(
            network("wlan0", NetworkCapabilities.TRANSPORT_WIFI),
            network("rmnet0", NetworkCapabilities.TRANSPORT_CELLULAR),   // reports UNSUPPORTED
        )
        rx["wlan0"] = 1_000L; tx["wlan0"] = 100L
        totalRx = 8_000_000L
        val snapshot = newSnapshot()
        snapshot.tick()
        snapshot.setCurrentAsLast()

        rx["wlan0"] = 1_700L
        totalRx = 9_000_000L
        snapshot.tick()

        assertEquals(700L, snapshot.downSpeed)
    }

    @Test
    fun `helper - all unsupported in both directions only`() {
        assertTrue(perInterfaceCountersUnsupported(listOf(UNSUPPORTED), listOf(UNSUPPORTED)))
        assertTrue(perInterfaceCountersUnsupported(listOf(UNSUPPORTED, UNSUPPORTED), listOf(UNSUPPORTED, UNSUPPORTED)))
        assertFalse(perInterfaceCountersUnsupported(listOf(UNSUPPORTED, 0L), listOf(UNSUPPORTED, UNSUPPORTED)))
        assertFalse(perInterfaceCountersUnsupported(listOf(UNSUPPORTED), listOf(5L)))
        assertFalse("a real counter of zero is supported, not unsupported", perInterfaceCountersUnsupported(listOf(0L), listOf(0L)))
        assertFalse("no interfaces is the caller's separate case", perInterfaceCountersUnsupported(emptyList(), emptyList()))
    }
}
