package com.leekleak.trafficlight.services.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class NotificationOrderingTest {

    @Test
    fun `offset is ten real years, not an Int-overflowed value`() {
        assertEquals(TimeUnit.DAYS.toMillis(3650), NOTIFICATION_ORDERING_OFFSET_MS)
        // The upstream bug: Int arithmetic wraps to 1_827_387_392 ms (~21 days).
        assertTrue(NOTIFICATION_ORDERING_OFFSET_MS > Int.MAX_VALUE)
    }

    @Test
    fun `when is exactly the offset ahead of now`() {
        val now = 1_786_000_000_000L
        assertEquals(now + NOTIFICATION_ORDERING_OFFSET_MS, notificationOrderingWhen(now))
    }

    @Test
    fun `sorts above ordinary notifications posted now or later`() {
        val now = System.currentTimeMillis()
        assertTrue(notificationOrderingWhen(now) > now + TimeUnit.DAYS.toMillis(365))
    }

    @Test
    fun `never approaches Long MAX_VALUE`() {
        val farFuture = 32_503_680_000_000L // year 3000
        val result = notificationOrderingWhen(farFuture)
        assertTrue(result > farFuture)
        assertTrue("must leave arithmetic headroom, unlike Long.MAX_VALUE", Long.MAX_VALUE - result > Long.MAX_VALUE / 2)
    }
}
