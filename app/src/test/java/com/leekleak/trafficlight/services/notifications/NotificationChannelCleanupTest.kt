package com.leekleak.trafficlight.services.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationChannelCleanupTest {

    private val manager: NotificationManager =
        (ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.NOTIFICATION_SERVICE)) as NotificationManager

    private fun create(vararg ids: String) = ids.forEach {
        manager.createNotificationChannel(NotificationChannel(it, "name of $it", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun existing() = manager.notificationChannels.map { it.id }.toSet()

    @Test
    fun `obsolete list never contains an active channel id`() {
        assertTrue(OBSOLETE_NOTIFICATION_CHANNEL_IDS.intersect(ACTIVE_NOTIFICATION_CHANNEL_IDS).isEmpty())
    }

    @Test
    fun `obsolete list covers the historical speed and plan channel ids`() {
        assertEquals(
            setOf("PersistentNotification", "PersistentNotification (Silent)", "PlanNotification", "PlanWarningNotification"),
            OBSOLETE_NOTIFICATION_CHANNEL_IDS,
        )
    }

    @Test
    fun `obsolete channels are deleted and active ones are untouched`() {
        create(
            SpeedNotification.NOTIFICATION_CHANNEL_ID,
            SpeedNotification.NOTIFICATION_CHANNEL_ID_SILENT,
            "PersistentNotification", "PersistentNotification (Silent)", "PlanNotification", "PlanWarningNotification",
        )

        val deleted = manager.deleteObsoleteChannels()

        assertEquals(OBSOLETE_NOTIFICATION_CHANNEL_IDS, deleted.toSet())
        assertEquals(ACTIVE_NOTIFICATION_CHANNEL_IDS, existing())
    }

    @Test
    fun `user-customised settings of an active channel survive`() {
        val active = NotificationChannel(SpeedNotification.NOTIFICATION_CHANNEL_ID, "Persistent Notification", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(active)
        create("PlanNotification")

        manager.deleteObsoleteChannels()

        val after = manager.getNotificationChannel(SpeedNotification.NOTIFICATION_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, after.importance)
    }

    @Test
    fun `channels that are not known to be obsolete are never deleted`() {
        create("gcm_fallback_notification_channel", "SomeFutureChannel", SpeedNotification.NOTIFICATION_CHANNEL_ID)

        val deleted = manager.deleteObsoleteChannels()

        assertTrue(deleted.isEmpty())
        assertTrue("gcm_fallback_notification_channel" in existing())
        assertTrue("SomeFutureChannel" in existing())
    }

    @Test
    fun `nothing to delete is a no-op and repeating is safe`() {
        create(SpeedNotification.NOTIFICATION_CHANNEL_ID)
        assertTrue(manager.deleteObsoleteChannels().isEmpty())
        assertTrue(manager.deleteObsoleteChannels().isEmpty())
    }

    @Test
    fun `selection ignores an id that is both listed obsolete and active`() {
        val result = obsoleteChannelsToDelete(
            existingIds = listOf("a", "b", "c"),
            obsolete = setOf("a", "b"),
            active = setOf("b"),
        )
        assertEquals(listOf("a"), result)
    }
}
