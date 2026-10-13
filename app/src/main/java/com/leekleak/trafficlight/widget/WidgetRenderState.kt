package com.leekleak.trafficlight.widget

import androidx.datastore.preferences.core.MutablePreferences

/*
 * A widget's Glance state records what it last rendered (LAST_USAGE/LAST_NETWORK) so that
 * Widget.provideGlance() can skip a redundant provideContent(). That record is the *only*
 * "is a refresh needed" state: forcing a refresh means forgetting it (forgetRendered), not
 * setting a second flag that could drift out of sync with it.
 *
 * The record is only valid while the widget's RemoteViews are still what was last pushed.
 * After a reboot or an app update the launcher falls back to the provider's initial
 * ("loading") layout while the stored record still says "up to date", so the next update
 * would be skipped and the widget would stay on that placeholder.
 */

/**
 * Records [usage]/[networkName] as the rendered state.
 * @return `true` if this differs from what was recorded before (i.e. the widget must render),
 * `false` if the widget is already showing exactly this.
 */
internal fun MutablePreferences.recordRendered(usage: String, networkName: String): Boolean {
    if (this[Widget.LAST_USAGE] == usage && this[Widget.LAST_NETWORK] == networkName) return false
    this[Widget.LAST_USAGE] = usage
    this[Widget.LAST_NETWORK] = networkName
    return true
}

/** Forgets the rendered state so the next [recordRendered] reports a change. Idempotent. */
internal fun MutablePreferences.forgetRendered() {
    remove(Widget.LAST_USAGE)
    remove(Widget.LAST_NETWORK)
}
