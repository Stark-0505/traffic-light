package com.leekleak.trafficlight.services.notifications

import android.app.NotificationManager
import timber.log.Timber

/**
 * Notification channel IDs this app has used in the past and no longer creates. They persist
 * in the system's notification settings after an update (channels are never removed
 * automatically), so a user upgrading from an older build - including the upstream builds
 * that shared this application ID - still sees them listed.
 *
 * Derived from the full history of `NOTIFICATION_CHANNEL_ID*` constants:
 * - `PersistentNotification`, `PersistentNotification (Silent)`: the speed notification's
 *   original IDs, replaced by the current ones below.
 * - `PlanNotification`, `PlanWarningNotification`: belonged to the data-plan feature, which
 *   this fork does not have.
 *
 * Deliberately an explicit list rather than "delete everything that is not current": that
 * would also delete channels created by libraries or by the system on the app's behalf, and
 * any channel a future version adds before this list is updated.
 */
internal val OBSOLETE_NOTIFICATION_CHANNEL_IDS: Set<String> = setOf(
    "PersistentNotification",
    "PersistentNotification (Silent)",
    "PlanNotification",
    "PlanWarningNotification",
)

/** The channels the app currently creates; these are never deleted. */
internal val ACTIVE_NOTIFICATION_CHANNEL_IDS: Set<String> = setOf(
    SpeedNotification.NOTIFICATION_CHANNEL_ID,
    SpeedNotification.NOTIFICATION_CHANNEL_ID_SILENT,
)

/** Which of [existingIds] are proven obsolete. An active ID is never returned. */
internal fun obsoleteChannelsToDelete(
    existingIds: Collection<String>,
    obsolete: Set<String> = OBSOLETE_NOTIFICATION_CHANNEL_IDS,
    active: Set<String> = ACTIVE_NOTIFICATION_CHANNEL_IDS,
): List<String> = existingIds.filter { it in obsolete && it !in active }

/**
 * Deletes obsolete channels that exist. Costs one Binder call to list the channels plus one
 * per channel actually deleted, so call it off the main thread.
 * @return the IDs deleted.
 */
internal fun NotificationManager.deleteObsoleteChannels(): List<String> {
    val toDelete = obsoleteChannelsToDelete(notificationChannels.map { it.id })
    toDelete.forEach { id ->
        Timber.i("Deleting obsolete notification channel %s", id)
        deleteNotificationChannel(id)
    }
    return toDelete
}
