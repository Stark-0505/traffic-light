package com.leekleak.trafficlight.services

import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the app puts back after the device reboots or the app is updated (both kill the
 * notification service and reset what widgets show):
 *
 * 1. refresh the cached permission state,
 * 2. restart the speed notification service - only if the user has that notification enabled,
 * 3. restore the widgets - only if at least one is placed: schedule the refresh alarm and
 *    force a re-render (a widget's cached "already rendered" state is stale after a restart).
 *
 * Each step is independent and best-effort: a failure in one is logged and does not stop the
 * others. [kotlin.coroutines.cancellation.CancellationException] always propagates. The
 * collaborators are injected so this is testable without Android.
 */
internal class RestartRestorer(
    private val updatePermissions: suspend () -> Unit,
    private val isSpeedNotificationEnabled: suspend () -> Boolean,
    private val startNotificationService: () -> Unit,
    private val placedWidgetCount: () -> Int,
    private val startWidgetAlarm: () -> Unit,
    private val forceRefreshWidgets: suspend () -> Unit,
) {
    suspend fun restore() {
        step("update permissions") { updatePermissions() }
        step("start notification service") {
            if (isSpeedNotificationEnabled()) startNotificationService()
        }
        val widgetCount = step("count widgets") { placedWidgetCount() } ?: 0
        if (widgetCount > 0) {
            // Alarm first: it is cheap and must not depend on the refresh succeeding.
            step("start widget alarm") { startWidgetAlarm() }
            step("refresh widgets") { forceRefreshWidgets() }
        }
    }

    private suspend fun <T> step(name: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Restart restore step failed: %s", name)
            null
        }
}
