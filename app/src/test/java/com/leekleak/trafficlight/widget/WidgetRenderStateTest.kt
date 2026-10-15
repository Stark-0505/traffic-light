package com.leekleak.trafficlight.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRenderStateTest {

    @Test
    fun `first render of a widget is a change`() {
        val prefs = mutablePreferencesOf()
        assertTrue(prefs.recordRendered("1.2 GB", "Mobile"))
        assertEquals("1.2 GB", prefs[Widget.LAST_USAGE])
        assertEquals("Mobile", prefs[Widget.LAST_NETWORK])
    }

    @Test
    fun `identical state is skipped`() {
        val prefs = mutablePreferencesOf()
        prefs.recordRendered("1.2 GB", "Mobile")
        assertFalse(prefs.recordRendered("1.2 GB", "Mobile"))
    }

    @Test
    fun `changed usage or changed network is a change`() {
        val prefs = mutablePreferencesOf()
        prefs.recordRendered("1.2 GB", "Mobile")
        assertTrue(prefs.recordRendered("1.3 GB", "Mobile"))
        assertTrue(prefs.recordRendered("1.3 GB", "Wifi"))
        assertFalse(prefs.recordRendered("1.3 GB", "Wifi"))
    }

    /** The regression: unchanged data must still re-render once the cache has been forgotten. */
    @Test
    fun `forgetting the rendered state forces the next identical render`() {
        val prefs = mutablePreferencesOf()
        prefs.recordRendered("1.2 GB", "Mobile")
        assertFalse("precondition: unchanged state is skipped", prefs.recordRendered("1.2 GB", "Mobile"))

        prefs.forgetRendered()

        assertNull(prefs[Widget.LAST_USAGE])
        assertNull(prefs[Widget.LAST_NETWORK])
        assertTrue("same data must render again after a forced refresh", prefs.recordRendered("1.2 GB", "Mobile"))
        assertFalse("and then go back to being skipped", prefs.recordRendered("1.2 GB", "Mobile"))
    }

    @Test
    fun `forgetting is idempotent and safe on a never-rendered widget`() {
        val prefs = mutablePreferencesOf()
        prefs.forgetRendered()
        prefs.forgetRendered()
        assertTrue(prefs.recordRendered("0 B", "Mobile"))
    }

    @Test
    fun `forgetting leaves unrelated widget state alone`() {
        val prefs = mutablePreferencesOf(Widget.NETWORK to "Wifi")
        prefs.recordRendered("5 MB", "Wifi")
        prefs.forgetRendered()
        assertEquals("the user's chosen network is configuration, not render cache", "Wifi", prefs[Widget.NETWORK])
    }
}
