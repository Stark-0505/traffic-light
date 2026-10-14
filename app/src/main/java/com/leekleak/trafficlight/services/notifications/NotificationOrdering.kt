package com.leekleak.trafficlight.services.notifications

/**
 * Ten years in milliseconds, as a `Long` from the first term on.
 *
 * `1000 * 60 * 60 * 24 * 365 * 10` is `Int` arithmetic and silently wraps to 1,827,387,392
 * (about 21 days). Upstream shipped exactly that as `DECADE`, so the `10L` literal matters.
 */
internal const val NOTIFICATION_ORDERING_OFFSET_MS: Long = 10L * 365 * 24 * 60 * 60 * 1000

/**
 * The `when` timestamp for a notification that should sort above ordinary ones.
 *
 * The notification shade orders by `when`, and the speed notification wants to stay on top
 * (its timestamp is hidden with `setShowWhen(false)`). A far-future time does that, but
 * `Long.MAX_VALUE` is too extreme a sentinel: upstream saw it overflow on Honor's Android
 * skin (almost certainly vendor code doing arithmetic on `when`; upstream 0da3814). Ten years
 * ahead of [nowMs] sorts above everything realistic and leaves ~9 orders of magnitude of
 * headroom below `Long.MAX_VALUE`.
 */
internal fun notificationOrderingWhen(nowMs: Long): Long = nowMs + NOTIFICATION_ORDERING_OFFSET_MS
