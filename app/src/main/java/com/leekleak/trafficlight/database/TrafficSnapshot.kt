package com.leekleak.trafficlight.database

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.SystemClock
import com.leekleak.trafficlight.model.DataUID
import com.leekleak.trafficlight.util.toLocaleHourString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.time.LocalDate
import java.time.LocalDateTime

data class DayUsage(
    val date: LocalDate = LocalDate.now(),
    val usage1: Long = 0L,
    val usage2: Long = 0L
) {
    val totalUsage: Long
        get() = usage1 + usage2
}

data class AppUsage(
    val app: DataUID,
    val usage: DayUsage,
)

data class HourUsage(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val usage: DayUsage,
) {
    fun toString(is24HourFormat: Boolean): String {
        return "${start.toLocalTime().toLocaleHourString(is24HourFormat)} - ${end.toLocalTime().toLocaleHourString(is24HourFormat)}"
    }
}

/**
 * Tracks device network throughput for the live speed notification.
 *
 * Measurement model
 * ------------------
 * "Speed" here means the rate of traffic actually crossing the device's physical
 * radios (Wi-Fi / cellular / Ethernet), not the rate observed at any single
 * interface. That distinction matters once a VPN (traditional, WireGuard, or a
 * local/loopback VPN such as AdGuard) is active: a VPN adds a virtual `tun`-style
 * interface that carries a second, parallel view of the same traffic. Summing
 * every interface (what [TrafficStats.getTotalRxBytes] does) therefore counts
 * VPN'd traffic twice - once on the physical interface, once on the tunnel - and
 * inflates the displayed speed, often close to 2x.
 *
 * To avoid that, [regularUpdateSnapshot] sums counters only for interfaces that
 * back a physical, non-VPN network ([physicalInterfaceNamesNow]), discovered from
 * [ConnectivityManager] rather than assumed names like "wlan0" or "tun0". A VPN's
 * underlying Wi-Fi/cellular network is still reported by the platform as its own
 * `Network` (without `TRANSPORT_VPN`) even while the VPN is active, so this
 * naturally excludes tunnel traffic without ever needing to detect or name the
 * VPN interface itself. It also means split tunneling, VPN reconnects, and VPN
 * on/off transitions don't require any special-casing: the physical interfaces
 * being summed don't change just because a VPN attaches to or detaches from them.
 *
 * Interface changes (Wi-Fi <-> mobile <-> Ethernet) *do* change which interfaces
 * are summed. [bytesPerSecond] guards against treating that as a traffic spike by
 * comparing a source key derived from the current interface set: if it differs
 * from the previous sample's, the measurement is rebaselined (reported as 0)
 * instead of diffing two unrelated counters.
 */
class TrafficSnapshot internal constructor(
    private val scope: CoroutineScope,
    private val appPreferenceRepo: AppPreferenceRepo,
    private val connectivityManager: ConnectivityManager,
    private val fallbackCounters: SysfsFallbackCounters = SysfsFallbackCounters(),
    // Where blocking counter reads run. `scope` is typically a lifecycleScope (i.e. Main),
    // so the dispatcher is explicit and injectable rather than inherited from the caller.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile private var lastDown: Long = 0
    @Volatile private var lastUp: Long = 0
    @Volatile private var currentDown: Long = 0
    @Volatile private var currentUp: Long = 0
    @Volatile private var lastSourceKey: String = ""
    @Volatile private var currentSourceKey: String = ""
    @Volatile private var lastElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    @Volatile private var currentElapsedRealtimeMs: Long = lastElapsedRealtimeMs
    @Volatile private var useFallback: Boolean = TrafficStats.getTotalTxBytes() == TrafficStats.UNSUPPORTED.toLong()

    // Interface discovery (ConnectivityManager.allNetworks + getNetworkCapabilities +
    // getLinkProperties per network - several Binder calls) used to run on every speed tick,
    // i.e. about once a second for as long as the notification showed. It now runs only when
    // needed, and always from sample() on the IO dispatcher:
    //
    //  - The NetworkCallback below does no Binder work itself: it only marks the cache dirty.
    //    A single connectivity change typically fires several callbacks back-to-back
    //    (available + capabilities + link properties), and capabilities callbacks also fire
    //    for signal-strength changes; marking dirty is idempotent, so any number of events
    //    between two ticks costs one discovery at the next tick, not one per event.
    //  - The cache starts empty and dirty, so construction (typically on the main thread)
    //    performs no Binder calls; the first sample discovers.
    //  - If the callback could not be registered, nothing would ever mark the cache dirty, so
    //    sample() instead re-discovers every POLLING_REFRESH_INTERVAL_MS. That keeps handovers
    //    and VPN attach/detach reflected without returning to a Binder query per tick.
    @Volatile private var cachedPhysicalInterfaces: List<String> = emptyList()
    private val interfacesDirty = AtomicBoolean(true)
    @Volatile private var callbackActive = false
    @Volatile private var lastInterfaceRefreshMs = 0L
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var loggedPerInterfaceUnsupported = false

    init {
        appPreferenceRepo.forceFallback
            .onEach { force -> useFallback = force || TrafficStats.getTotalTxBytes() == TrafficStats.UNSUPPORTED.toLong() }
            .launchIn(scope)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = markInterfacesDirty()
            override fun onLost(network: Network) = markInterfacesDirty()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = markInterfacesDirty()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = markInterfacesDirty()
        }
        try {
            connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            networkCallback = callback
            callbackActive = true
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to register network callback; polling interface discovery every ${POLLING_REFRESH_INTERVAL_MS}ms instead")
        }
        // Whichever CoroutineScope this TrafficSnapshot belongs to ends together with the
        // thing that owns it (the notification service's lifecycleScope) - tying the
        // callback's unregistration to that Job means a leaked TrafficSnapshot can't also
        // leak a registered NetworkCallback.
        scope.coroutineContext[Job]?.invokeOnCompletion { stopTrackingNetworkChanges() }
    }

    /** Unregisters the network callback. Safe to call more than once. */
    fun stopTrackingNetworkChanges() {
        networkCallback?.let {
            networkCallback = null
            // No more events will mark the cache dirty; if this snapshot is sampled again it
            // must poll rather than trust a cache nothing can invalidate.
            callbackActive = false
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                // Already unregistered (e.g. the callback object was never actually
                // registered because registerNetworkCallback() above had thrown).
            }
        }
    }

    private fun markInterfacesDirty() {
        interfacesDirty.set(true)
    }

    /** Re-discovers physical interfaces if flagged dirty, or (no callback) if the cache is old. */
    private fun refreshInterfacesIfNeeded(nowMs: Long) {
        // Cleared *before* querying: an event that arrives during the query re-marks the
        // cache dirty and is picked up by the next tick instead of being lost.
        val dirty = interfacesDirty.getAndSet(false)
        val stale = !callbackActive && nowMs - lastInterfaceRefreshMs >= POLLING_REFRESH_INTERVAL_MS
        if (!dirty && !stale) return
        lastInterfaceRefreshMs = nowMs
        try {
            cachedPhysicalInterfaces = physicalInterfaceNamesNow()
        } catch (e: SecurityException) {
            // Keep the last known interfaces: better a possibly outdated set (a source change
            // is still caught by the rebaseline) than none. A later event or poll retries.
            Timber.e(e, "Interface discovery denied; keeping previous interfaces")
        }
    }

    val totalSpeed: Long
        get() = upSpeed + downSpeed

    val upSpeed: Long
        get() = bytesPerSecond(currentUp, lastUp, currentSourceKey, lastSourceKey, currentElapsedRealtimeMs, lastElapsedRealtimeMs)

    val downSpeed: Long
        get() = bytesPerSecond(currentDown, lastDown, currentSourceKey, lastSourceKey, currentElapsedRealtimeMs, lastElapsedRealtimeMs)

    fun setCurrentAsLast() {
        lastDown = currentDown
        lastUp = currentUp
        lastSourceKey = currentSourceKey
        lastElapsedRealtimeMs = currentElapsedRealtimeMs
    }

    /**
     * Takes a new counter sample. Suspends on [ioDispatcher] for the whole sample - the
     * TrafficStats queries and, in fallback mode, the sysfs file reads - so it is safe to
     * call from a main-dispatcher scope, and costs one dispatcher switch per tick.
     *
     * The sample is written to the `current*` fields only from within this block, and the
     * only readers ([upSpeed], [downSpeed], [isCurrentSameAsLast], [setCurrentAsLast]) run
     * after this call returns in the same coroutine, so a sample is never observed half-written.
     */
    suspend fun updateSnapshot() {
        withContext(ioDispatcher) { sample() }
    }

    private fun sample() {
        currentElapsedRealtimeMs = SystemClock.elapsedRealtime()
        if (useFallback) {
            try {
                fallbackUpdateSnapshot()
            } catch (e: IOException) {
                disableFallback("Fallback IO error", e)
            } catch (e: NumberFormatException) {
                disableFallback("Fallback number format error", e)
            } catch (e: SecurityException) {
                // The device's SELinux policy can deny raw sysfs traversal
                // (/sys/class/net/*) to untrusted apps even when the file's plain Unix
                // permissions look readable - canRead() only checks the latter. This
                // fallback is a last resort for devices without a working TrafficStats,
                // so if the OS won't allow it either, stop retrying it every tick.
                disableFallback("Fallback denied by SELinux policy", e)
            }
            // CancellationException is deliberately not caught: withContext() cancellation
            // surfaces at the dispatcher boundary and must propagate to the caller.
        } else {
            regularUpdateSnapshot()
        }
    }

    private fun disableFallback(reason: String, e: Exception) {
        Timber.e(e, reason)
        scope.launch { appPreferenceRepo.setForceFallback(false) }
        useFallback = false
    }

    private fun regularUpdateSnapshot() {
        refreshInterfacesIfNeeded(currentElapsedRealtimeMs)
        val interfaces = cachedPhysicalInterfaces
        if (interfaces.isEmpty()) {
            // No physical (Wi-Fi/cellular/Ethernet) network is visible to this app right
            // now - can happen transiently during a network handover, or on an unusual
            // setup where only a VPN network is reported (or the platform hides networks
            // altogether). Total counters are the best remaining source, even though they
            // can double-count VPN traffic; there is no physical-only source left.
            sampleDeviceTotals()
            return
        }

        val rx = interfaces.map { TrafficStats.getRxBytes(it) }
        val tx = interfaces.map { TrafficStats.getTxBytes(it) }
        if (perInterfaceCountersUnsupported(rx, tx)) {
            // The platform does not serve per-interface counters to this app (upstream only
            // used them on API 31+; a hard SDK cut-off is not assumed here, the platform's
            // own answer decides). Without this, every interface would read as 0 and the
            // speed would be stuck at 0 even though traffic is flowing.
            if (!loggedPerInterfaceUnsupported) {
                loggedPerInterfaceUnsupported = true
                Timber.w("Per-interface TrafficStats unsupported for %s; using device totals", interfaces)
            }
            sampleDeviceTotals()
            return
        }

        currentSourceKey = interfaces.sorted().joinToString(",")
        currentDown = rx.sumOf { it.unsupportedAsZero() }
        currentUp = tx.sumOf { it.unsupportedAsZero() }

        // Fun fact: when switching networks the API sometimes messes up the values as per
        // https://issuetracker.google.com/issues/37009612
        // Yes. That bug report is from 2014.
        // Yes. It still happens on recent Android versions.
        // The source-key rebaseline in bytesPerSecond() covers a network switch changing
        // which interfaces are summed, but not this: a transient bad reading from the
        // platform itself on the interfaces we're already tracking.
    }

    private fun sampleDeviceTotals() {
        currentSourceKey = SOURCE_TOTAL
        currentDown = TrafficStats.getTotalRxBytes()
        currentUp = TrafficStats.getTotalTxBytes()
    }

    private fun Long.unsupportedAsZero(): Long = if (this == TrafficStats.UNSUPPORTED.toLong()) 0L else this

    private fun fallbackUpdateSnapshot() {
        // Read everything first and publish afterwards, so a failure part-way through
        // (unreadable or malformed file) leaves the previous sample fully intact instead
        // of a mix of old and new values.
        val reading = fallbackCounters.read()
        currentSourceKey = SOURCE_FALLBACK
        currentUp = reading.up
        currentDown = reading.down
    }

    fun isCurrentSameAsLast(): Boolean = lastDown == currentDown && lastUp == currentUp

    companion object {
        private const val SOURCE_TOTAL = "total"
        private const val SOURCE_FALLBACK = "fallback"

        /** Re-discovery period when no NetworkCallback is available to flag changes. */
        internal const val POLLING_REFRESH_INTERVAL_MS = 2_000L

        private val defaultFallbackCounters: SysfsFallbackCounters by lazy { SysfsFallbackCounters() }

        /**
         * Whether the sysfs fallback can read any counter on this device. Touches the
         * filesystem, so it suspends on the IO dispatcher - never call it during
         * composition; collect it from a coroutine (e.g. `produceState`) instead.
         */
        suspend fun doesFallbackWork(): Boolean = doesFallbackWork(defaultFallbackCounters, Dispatchers.IO)

        internal suspend fun doesFallbackWork(
            counters: SysfsFallbackCounters,
            ioDispatcher: CoroutineDispatcher,
        ): Boolean = withContext(ioDispatcher) { counters.isAvailable() }
    }
}

/**
 * Converts two counter samples into an instantaneous bytes-per-second rate.
 *
 * Returns 0 instead of a computed rate when:
 * - there is no previous sample yet ([lastSourceKey] empty on the very first call),
 * - the measurement source changed between samples (interface handover, VPN
 *   attaching/detaching from the physical interface set, fallback mode toggling) -
 *   the two counters aren't comparable, so this rebaselines instead of reporting
 *   whatever gap happens to exist between two unrelated counters,
 * - the counter went backwards (device reboot, a driver resetting its interface
 *   counters, counter wraparound) - this never derives a negative or
 *   overflow-driven speed from that.
 *
 * Elapsed time is measured directly rather than assumed, since the actual gap
 * between samples varies with scheduling delays.
 */
internal fun bytesPerSecond(
    currentBytes: Long,
    lastBytes: Long,
    currentSourceKey: String,
    lastSourceKey: String,
    currentElapsedRealtimeMs: Long,
    lastElapsedRealtimeMs: Long,
): Long {
    if (currentSourceKey != lastSourceKey) return 0L
    val deltaBytes = currentBytes - lastBytes
    if (deltaBytes <= 0L) return 0L
    val elapsedMs = (currentElapsedRealtimeMs - lastElapsedRealtimeMs).coerceAtLeast(1L)
    return (deltaBytes.toDouble() * 1000.0 / elapsedMs).toLong()
}

/**
 * `true` when the platform answered `TrafficStats.UNSUPPORTED` for *every* per-interface
 * counter, in both directions - i.e. it does not provide per-interface statistics to this app
 * at all, as opposed to one interface being momentarily absent. Empty input is not
 * "unsupported" (no interfaces is handled separately by the caller).
 */
internal fun perInterfaceCountersUnsupported(rx: List<Long>, tx: List<Long>): Boolean {
    val unsupported = TrafficStats.UNSUPPORTED.toLong()
    return (rx.isNotEmpty() || tx.isNotEmpty()) && rx.all { it == unsupported } && tx.all { it == unsupported }
}
