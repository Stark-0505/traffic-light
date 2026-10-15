package com.leekleak.trafficlight.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetAlarmControllerTest {

    private val calls = mutableListOf<String>()

    private fun controller(widgets: Int) = WidgetAlarmController(
        placedWidgetCount = { widgets },
        startAlarm = { calls += "start" },
        cancelAlarm = { calls += "cancel" },
    )

    @Test
    fun `zero widgets - sync cancels instead of scheduling`() {
        assertFalse(controller(0).syncWithWidgets())
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun `one widget - sync schedules`() {
        assertTrue(controller(1).syncWithWidgets())
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun `multiple widgets - sync schedules exactly once`() {
        assertTrue(controller(4).syncWithWidgets())
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun `alarm tick with widgets never reschedules or cancels`() {
        assertTrue(controller(2).cancelIfNoWidgets())
        assertTrue("re-registering from the alarm's own tick would re-fire it immediately", calls.isEmpty())
    }

    @Test
    fun `alarm tick with no widgets cancels the stale alarm and skips the update`() {
        assertFalse(controller(0).cancelIfNoWidgets())
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun `a failing widget count propagates to the caller and touches no alarm`() {
        val failing = WidgetAlarmController(
            placedWidgetCount = { throw IllegalStateException("binder died") },
            startAlarm = { calls += "start" },
            cancelAlarm = { calls += "cancel" },
        )
        assertThrows(IllegalStateException::class.java) { failing.syncWithWidgets() }
        assertTrue(calls.isEmpty())
    }
}
