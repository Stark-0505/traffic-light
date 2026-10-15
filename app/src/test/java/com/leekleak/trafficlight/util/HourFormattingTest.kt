package com.leekleak.trafficlight.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

/** Pure formatter tests: the clock preference is an argument, no Android involved. */
class HourFormattingTest {

    private val afternoon = LocalTime.of(13, 5)
    private val midnight = LocalTime.MIDNIGHT

    @Test
    fun `24-hour mode ignores the short flag`() {
        assertEquals("13:05", afternoon.toLocaleHourString(true, locale = Locale.US))
        assertEquals("13:05", afternoon.toLocaleHourString(true, short = true, locale = Locale.US))
        assertEquals("00:00", midnight.toLocaleHourString(true, locale = Locale.US))
    }

    @Test
    fun `12-hour mode uses am-pm and honours the short flag`() {
        assertEquals("01:05 PM", afternoon.toLocaleHourString(false, locale = Locale.US))
        assertEquals("01 PM", afternoon.toLocaleHourString(false, short = true, locale = Locale.US))
        assertEquals("12:00 AM", midnight.toLocaleHourString(false, locale = Locale.US))
    }

    @Test
    fun `the same time renders differently when the preference flips`() {
        val time = LocalTime.of(18, 30)
        assertEquals("18:30", time.toLocaleHourString(true, locale = Locale.US))
        assertEquals("06:30 PM", time.toLocaleHourString(false, locale = Locale.US))
    }
}
