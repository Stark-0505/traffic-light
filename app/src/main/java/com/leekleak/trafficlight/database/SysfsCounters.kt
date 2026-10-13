package com.leekleak.trafficlight.database

import java.io.File

/**
 * Minimal filesystem boundary for the sysfs traffic-counter fallback.
 *
 * Every method here is **blocking** and must only be called from a background
 * dispatcher (see [TrafficSnapshot.updateSnapshot] and [TrafficSnapshot.doesFallbackWork],
 * which own the `withContext(Dispatchers.IO)` boundary). It exists so unit tests can
 * substitute fake counters instead of depending on the real `/sys/class/net` tree.
 */
internal interface SysfsReader {
    /** `true` when the file exists and plain Unix permissions allow reading it. */
    fun canRead(path: String): Boolean

    /**
     * Raw file contents.
     * @throws java.io.IOException if the file disappears or cannot be read.
     * @throws SecurityException if the OS denies access (e.g. SELinux policy).
     */
    fun readText(path: String): String
}

internal object FileSysfsReader : SysfsReader {
    override fun canRead(path: String): Boolean = File(path).canRead()
    override fun readText(path: String): String = File(path).readText()
}

/**
 * Parses the contents of a `rx_bytes`/`tx_bytes` file under `/sys/class/net/<interface>/statistics`.
 *
 * @throws NumberFormatException for empty, non-numeric or out-of-range content.
 */
internal fun parseSysfsCounter(raw: String): Long = raw.trim().toLong()

internal data class FallbackReading(val up: Long, val down: Long)

/**
 * Last-resort traffic counters read straight from sysfs, for devices on which
 * [android.net.TrafficStats] is unsupported. Interface names are the conventional
 * ones; unlike the main path this cannot tell a VPN apart, but the VPN tunnel
 * interface is simply never among the paths read.
 *
 * All methods are blocking - call them from a background dispatcher only.
 */
internal class SysfsFallbackCounters(private val reader: SysfsReader = FileSysfsReader) {

    /** Whether at least one of the receive counters can be read on this device. */
    fun isAvailable(): Boolean =
        reader.canRead(MOBILE_RX) || reader.canRead(WIFI_RX) || reader.canRead(ETH_RX)

    /**
     * Sums the mobile, Wi-Fi and Ethernet counters. A counter file that does not exist
     * or is not readable contributes 0 (a Wi-Fi-only device has no `rmnet0`), but a file
     * that exists and holds garbage or cannot actually be opened propagates its exception
     * so the caller can stop using the fallback.
     */
    fun read(): FallbackReading {
        val up = readOrZero(MOBILE_TX) + readOrZero(WIFI_TX) + readOrZero(ETH_TX)
        val down = readOrZero(MOBILE_RX) + readOrZero(WIFI_RX) + readOrZero(ETH_RX)
        return FallbackReading(up = up, down = down)
    }

    private fun readOrZero(path: String): Long =
        if (reader.canRead(path)) parseSysfsCounter(reader.readText(path)) else 0L

    internal companion object {
        const val MOBILE_RX = "/sys/class/net/rmnet0/statistics/rx_bytes"
        const val MOBILE_TX = "/sys/class/net/rmnet0/statistics/tx_bytes"
        const val WIFI_RX = "/sys/class/net/wlan0/statistics/rx_bytes"
        const val WIFI_TX = "/sys/class/net/wlan0/statistics/tx_bytes"
        const val ETH_RX = "/sys/class/net/eth0/statistics/rx_bytes"
        const val ETH_TX = "/sys/class/net/eth0/statistics/tx_bytes"
    }
}
