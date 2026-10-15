package com.leekleak.trafficlight.widget

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForceRefreshTest {

    private val calls = mutableListOf<String>()

    @Test
    fun `zero widgets does nothing`() = runTest {
        val refreshed = forceRefresh(emptyList<Int>(), { calls += "inv$it" }, { calls += "upd$it" })
        assertEquals(0, refreshed)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `one widget is invalidated before it is updated`() = runTest {
        val refreshed = forceRefresh(listOf(7), { calls += "inv$it" }, { calls += "upd$it" })
        assertEquals(1, refreshed)
        assertEquals(listOf("inv7", "upd7"), calls)
    }

    @Test
    fun `multiple widgets are each invalidated and updated`() = runTest {
        val refreshed = forceRefresh(listOf(1, 2, 3), { calls += "inv$it" }, { calls += "upd$it" })
        assertEquals(3, refreshed)
        assertEquals(listOf("inv1", "upd1", "inv2", "upd2", "inv3", "upd3"), calls)
    }

    @Test
    fun `a failing widget does not stop the others`() = runTest {
        val refreshed = forceRefresh(
            ids = listOf(1, 2, 3),
            invalidate = { calls += "inv$it" },
            update = { if (it == 2) throw IllegalStateException("launcher gone") else calls += "upd$it" },
        )
        assertEquals(2, refreshed)
        assertEquals(listOf("inv1", "upd1", "inv2", "inv3", "upd3"), calls)
    }

    @Test
    fun `a failing invalidation skips only that widget's update`() = runTest {
        val refreshed = forceRefresh(
            ids = listOf(1, 2),
            invalidate = { if (it == 1) throw IOException("datastore corrupt") else calls += "inv$it" },
            update = { calls += "upd$it" },
        )
        assertEquals(1, refreshed)
        assertEquals(listOf("inv2", "upd2"), calls)
    }

    @Test
    fun `cancellation propagates instead of being logged away`() = runTest {
        var propagated = false
        try {
            forceRefresh(listOf(1, 2), { }, { throw CancellationException("scope cancelled") })
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }
}
