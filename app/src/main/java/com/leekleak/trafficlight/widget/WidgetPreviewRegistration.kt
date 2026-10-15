package com.leekleak.trafficlight.widget

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Apparently, if widget previews are refreshed too soon after app launch they are ignored.
 */
internal val WIDGET_PREVIEW_DELAY: Duration = 1.seconds

/**
 * Registers generated widget previews (`GlanceAppWidgetManager.setWidgetPreviews`) without
 * running the work on the caller's dispatcher.
 *
 * The caller is typically `lifecycleScope` (Main). Registering previews composes the widget's
 * preview content and talks to AppWidgetManager over Binder - neither is UI work. The fork's
 * own widget updates already compose off Main ([WidgetReceiver] and [WidgetUpdateReceiver]
 * launch on `applicationScope`, which is `Dispatchers.Default`), and upstream registers
 * previews on IO as well. Only the [initialDelay] wait stays on the caller's dispatcher (it
 * is a suspension, not blocking); the registration itself runs on [ioDispatcher].
 *
 * Cancelling the caller (e.g. the activity being destroyed during the delay) cancels the
 * registration; the resulting [CancellationException] is rethrown. Any other failure is
 * logged and swallowed - preview registration is best-effort and must never crash the app.
 */
internal suspend fun registerWidgetPreviews(
    initialDelay: Duration = WIDGET_PREVIEW_DELAY,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    register: suspend () -> Unit,
) {
    delay(initialDelay)
    try {
        withContext(ioDispatcher) { register() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to register widget previews")
    }
}
