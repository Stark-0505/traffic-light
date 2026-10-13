package com.leekleak.trafficlight.services

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/** Boot / app-update restoration, with every collaborator faked. */
class RestartRestorerTest {

    private val calls = mutableListOf<String>()

    private fun restorer(
        notificationEnabled: Boolean = true,
        widgets: Int = 1,
        updatePermissions: suspend () -> Unit = { calls += "permissions" },
        startService: () -> Unit = { calls += "service" },
        countWidgets: () -> Int = { widgets },
        startAlarm: () -> Unit = { calls += "alarm" },
        refresh: suspend () -> Unit = { calls += "refresh" },
    ) = RestartRestorer(
        updatePermissions = updatePermissions,
        isSpeedNotificationEnabled = { notificationEnabled },
        startNotificationService = startService,
        placedWidgetCount = countWidgets,
        startWidgetAlarm = startAlarm,
        forceRefreshWidgets = refresh,
    )

    @Test
    fun `zero widgets - no alarm and no refresh, service still restored`() = runTest {
        restorer(widgets = 0).restore()
        assertEquals(listOf("permissions", "service"), calls)
    }

    @Test
    fun `one widget - alarm scheduled and widgets force refreshed`() = runTest {
        restorer(widgets = 1).restore()
        assertEquals(listOf("permissions", "service", "alarm", "refresh"), calls)
    }

    @Test
    fun `multiple widgets - alarm and refresh happen once, not per widget`() = runTest {
        restorer(widgets = 3).restore()
        assertEquals(1, calls.count { it == "alarm" })
        assertEquals(1, calls.count { it == "refresh" })
    }

    @Test
    fun `notification disabled - service is not started but widgets are still restored`() = runTest {
        restorer(notificationEnabled = false, widgets = 2).restore()
        assertEquals(listOf("permissions", "alarm", "refresh"), calls)
    }

    @Test
    fun `permission update failure does not block the rest`() = runTest {
        restorer(updatePermissions = { throw SecurityException("denied") }).restore()
        assertEquals(listOf("service", "alarm", "refresh"), calls)
    }

    @Test
    fun `service start failure does not block widgets`() = runTest {
        restorer(startService = { throw IllegalStateException("background limits") }).restore()
        assertEquals(listOf("permissions", "alarm", "refresh"), calls)
    }

    @Test
    fun `refresh failure leaves the alarm scheduled`() = runTest {
        restorer(refresh = { throw IllegalStateException("glance failed") }).restore()
        assertTrue("alarm" in calls)
    }

    @Test
    fun `alarm failure does not stop the refresh`() = runTest {
        restorer(startAlarm = { throw SecurityException("exact alarm denied") }).restore()
        assertTrue("refresh" in calls)
    }

    @Test
    fun `widget count failure is treated as no widgets`() = runTest {
        restorer(countWidgets = { throw RuntimeException("binder died") }).restore()
        assertEquals(listOf("permissions", "service"), calls)
    }

    @Test
    fun `cancellation propagates`() = runTest {
        var propagated = false
        try {
            restorer(updatePermissions = { throw CancellationException("cancelled") }).restore()
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertTrue("later steps must not run after cancellation", calls.isEmpty())
    }
}
