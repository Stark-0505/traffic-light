package com.leekleak.trafficlight.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long goAsync() broadcast work may run. A receiver that holds its PendingResult for
 * ~10s (foreground broadcasts) is reported as ANR, so work is cut off a little before that.
 */
internal val BROADCAST_WORK_BUDGET: Duration = 8.seconds

/**
 * Runs the work of a `goAsync()` broadcast receiver on this scope and guarantees that
 * [finish] (i.e. `PendingResult.finish()`) is called exactly once however the work ends:
 *
 * - normally,
 * - by throwing (the failure is logged; it does not escape into the app-wide scope, where it
 *   would crash the process, nor skip [finish]),
 * - by cancellation (the scope being cancelled; [CancellationException] is still propagated),
 * - by exceeding [budget] (logged; the work is cancelled so a stuck widget update cannot hold
 *   the broadcast open until the system reports an ANR).
 */
internal fun CoroutineScope.launchBroadcastWork(
    finish: () -> Unit,
    budget: Duration = BROADCAST_WORK_BUDGET,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    try {
        val completed = withTimeoutOrNull(budget) {
            block()
            true
        } ?: false
        if (!completed) Timber.w("Broadcast work exceeded %s and was cancelled", budget)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Broadcast work failed")
    } finally {
        finish()
    }
}
