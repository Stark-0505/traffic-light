package com.leekleak.trafficlight

import android.os.StrictMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugDiagnosticsTest {

    @Before
    fun resetPolicies() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
    }

    @After
    fun restorePolicies() = resetPolicies()

    /** What a release build does: BuildConfig.DEBUG == false is passed as `enabled`. */
    @Test
    fun `disabled leaves both policies untouched`() {
        val thread = StrictMode.getThreadPolicy().toString()
        val vm = StrictMode.getVmPolicy().toString()

        configureStrictMode(enabled = false)

        assertEquals(thread, StrictMode.getThreadPolicy().toString())
        assertEquals(vm, StrictMode.getVmPolicy().toString())
    }

    @Test
    fun `enabled installs both a thread and a vm policy`() {
        val thread = StrictMode.getThreadPolicy().toString()
        val vm = StrictMode.getVmPolicy().toString()

        configureStrictMode(enabled = true)

        assertNotEquals(thread, StrictMode.getThreadPolicy().toString())
        assertNotEquals(vm, StrictMode.getVmPolicy().toString())
    }

    @Test
    fun `enabling twice is idempotent`() {
        configureStrictMode(enabled = true)
        val thread = StrictMode.getThreadPolicy().toString()
        val vm = StrictMode.getVmPolicy().toString()

        configureStrictMode(enabled = true)

        assertEquals(thread, StrictMode.getThreadPolicy().toString())
        assertEquals(vm, StrictMode.getVmPolicy().toString())
    }

    @Test
    fun `older API levels skip the API 31 check and still install`() {
        val vm = StrictMode.getVmPolicy().toString()

        configureStrictMode(enabled = true, sdkInt = 28)

        assertNotEquals(vm, StrictMode.getVmPolicy().toString())
    }

    @Test
    fun `api 31 and later enable one more vm check than api 28`() {
        configureStrictMode(enabled = true, sdkInt = 28)
        val old = StrictMode.getVmPolicy().toString()
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)

        configureStrictMode(enabled = true, sdkInt = 31)

        assertNotEquals(old, StrictMode.getVmPolicy().toString())
    }
}
