package com.leekleak.trafficlight.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/** Pure-JVM tests: parsing and summing of the sysfs fallback counters, no Android framework. */
class SysfsFallbackCountersTest {

    private val mobileRx = SysfsFallbackCounters.MOBILE_RX
    private val mobileTx = SysfsFallbackCounters.MOBILE_TX
    private val wifiRx = SysfsFallbackCounters.WIFI_RX
    private val wifiTx = SysfsFallbackCounters.WIFI_TX
    private val ethRx = SysfsFallbackCounters.ETH_RX
    private val ethTx = SysfsFallbackCounters.ETH_TX

    @Test
    fun `parse accepts plain and newline-terminated values`() {
        assertEquals(0L, parseSysfsCounter("0"))
        assertEquals(123_456_789L, parseSysfsCounter("123456789\n"))
        assertEquals(42L, parseSysfsCounter("  42 \n"))
        assertEquals(Long.MAX_VALUE, parseSysfsCounter(Long.MAX_VALUE.toString()))
    }

    @Test
    fun `parse rejects malformed and out-of-range values`() {
        listOf("", "\n", "abc", "12 34", "1.5", "0x10", "18446744073709551615").forEach { raw ->
            assertThrows("input <$raw>", NumberFormatException::class.java) { parseSysfsCounter(raw) }
        }
    }

    @Test
    fun `sums mobile, wifi and ethernet independently for upload and download`() {
        val counters = SysfsFallbackCounters(
            FakeSysfsReader(
                mapOf(
                    mobileRx to "1000\n", mobileTx to "100\n",
                    wifiRx to "20000\n", wifiTx to "2000\n",
                    ethRx to "300000\n", ethTx to "30000\n",
                )
            )
        )
        val reading = counters.read()
        assertEquals(321_000L, reading.down)
        assertEquals(32_100L, reading.up)
    }

    @Test
    fun `upload and download are independent when only one direction is present`() {
        val counters = SysfsFallbackCounters(FakeSysfsReader(mapOf(wifiTx to "777")))
        assertEquals(FallbackReading(up = 777L, down = 0L), counters.read())
    }

    @Test
    fun `missing interface files contribute zero`() {
        // Wi-Fi-only device: no rmnet0, no eth0.
        val counters = SysfsFallbackCounters(FakeSysfsReader(mapOf(wifiRx to "500", wifiTx to "50")))
        assertEquals(FallbackReading(up = 50L, down = 500L), counters.read())
    }

    @Test
    fun `no interfaces at all reads as zero and is unavailable`() {
        val counters = SysfsFallbackCounters(FakeSysfsReader())
        assertEquals(FallbackReading(0L, 0L), counters.read())
        assertFalse(counters.isAvailable())
    }

    @Test
    fun `an existing but unreadable file is treated like a missing one`() {
        val reader = FakeSysfsReader(mapOf(wifiRx to "500", wifiTx to "50", mobileRx to "9", mobileTx to "9"))
        reader.unreadable += setOf(mobileRx, mobileTx)
        assertEquals(FallbackReading(up = 50L, down = 500L), SysfsFallbackCounters(reader).read())
    }

    @Test
    fun `availability needs at least one readable receive counter`() {
        assertTrue(SysfsFallbackCounters(FakeSysfsReader(mapOf(mobileRx to "1"))).isAvailable())
        assertTrue(SysfsFallbackCounters(FakeSysfsReader(mapOf(wifiRx to "1"))).isAvailable())
        assertTrue(SysfsFallbackCounters(FakeSysfsReader(mapOf(ethRx to "1"))).isAvailable())
        // Transmit-only files do not make the fallback usable (matches the original probe).
        assertFalse(SysfsFallbackCounters(FakeSysfsReader(mapOf(wifiTx to "1", ethTx to "1"))).isAvailable())
        val denied = FakeSysfsReader(mapOf(wifiRx to "1")).apply { unreadable += wifiRx }
        assertFalse(SysfsFallbackCounters(denied).isAvailable())
    }

    @Test
    fun `malformed content propagates so the caller can drop the fallback`() {
        val counters = SysfsFallbackCounters(FakeSysfsReader(mapOf(wifiRx to "not-a-number", wifiTx to "5")))
        assertThrows(NumberFormatException::class.java) { counters.read() }
    }

    @Test
    fun `io and security failures propagate unchanged`() {
        val files = mapOf(wifiRx to "1", wifiTx to "1")
        val io = FakeSysfsReader(files).apply { readFailure = FileNotFoundException("vanished") }
        assertThrows(IOException::class.java) { SysfsFallbackCounters(io).read() }

        val selinux = FakeSysfsReader(files).apply { readFailure = SecurityException("avc: denied") }
        assertThrows(SecurityException::class.java) { SysfsFallbackCounters(selinux).read() }
    }
}
