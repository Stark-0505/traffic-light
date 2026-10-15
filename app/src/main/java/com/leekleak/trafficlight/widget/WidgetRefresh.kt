package com.leekleak.trafficlight.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * Re-renders every placed [Widget] even if its data is unchanged, for events after which the
 * launcher no longer shows what was last pushed (reboot, app update). See
 * [forgetRendered]. With no widgets placed this does nothing.
 *
 * @return how many widgets were refreshed successfully.
 */
suspend fun forceRefreshWidgets(context: Context): Int {
    val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(Widget::class.java)
    return forceRefresh(
        ids = glanceIds,
        invalidate = { id ->
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply { forgetRendered() }
            }
        },
        update = { id -> Widget().update(context, id) },
    )
}

/**
 * Invalidates then updates each of [ids] independently: one widget failing must not leave the
 * others stale. Failures are logged; [CancellationException] propagates.
 * @return the number of ids for which both steps succeeded.
 */
internal suspend fun <Id> forceRefresh(
    ids: Collection<Id>,
    invalidate: suspend (Id) -> Unit,
    update: suspend (Id) -> Unit,
): Int {
    var refreshed = 0
    for (id in ids) {
        try {
            invalidate(id)
            update(id)
            refreshed++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to force-refresh widget %s", id)
        }
    }
    return refreshed
}
