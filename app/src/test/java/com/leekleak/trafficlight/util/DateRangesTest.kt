package com.leekleak.trafficlight.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Boundary correctness for the single source of truth every screen's Day/Week/Month
 * grouping is built from. A bug here would silently misalign totals between screens
 * (e.g. History showing a different week total than Overview's weekly trend) rather than
 * crash, so these are worth pinning down explicitly.
 *
 * Locale.US is fixed for the duration of these tests (restored afterward) so week-start
 * assertions are deterministic: Locale.US's week starts on Sunday. [weekFields] itself is
 * still exercised for real, not stubbed - this only controls which locale it reads.
 */
class DateRangesTest {
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `startOfWeek returns the Sunday on or before a mid-week date, for a US locale`() {
        // Wednesday, Aug 27, 2025
        val wednesday = LocalDate.of(2025, 8, 27)
        val start = wednesday.startOfWeek()

        assertEquals(DayOfWeek.SUNDAY, start.dayOfWeek)
        assertEquals(LocalDate.of(2025, 8, 24), start)
    }

    @Test
    fun `startOfWeek on the week's first day returns that same date`() {
        val sunday = LocalDate.of(2025, 8, 24)
        assertEquals(sunday, sunday.startOfWeek())
    }

    @Test
    fun `endOfWeekExclusive is exactly seven days after startOfWeek`() {
        val anyDate = LocalDate.of(2025, 8, 27)
        val start = anyDate.startOfWeek()
        val endExclusive = anyDate.endOfWeekExclusive()

        assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(start, endExclusive))
        // The range end is exclusive, so the last day actually in the week is one before it.
        assertEquals(start.plusDays(6), endExclusive.minusDays(1))
    }

    @Test
    fun `a week crossing a month boundary keeps its days contiguous`() {
        // Aug 31, 2025 is a Sunday - the week runs Aug 31 through Sep 6.
        val date = LocalDate.of(2025, 8, 31)
        val start = date.startOfWeek()
        val endExclusive = date.endOfWeekExclusive()

        assertEquals(LocalDate.of(2025, 8, 31), start)
        assertEquals(LocalDate.of(2025, 9, 7), endExclusive)
        assertTrue(start.month != endExclusive.minusDays(1).month)
    }

    @Test
    fun `a week crossing a year boundary keeps its days contiguous`() {
        // Dec 28, 2025 is a Sunday - the week runs into January 2026.
        val date = LocalDate.of(2025, 12, 30)
        val start = date.startOfWeek()
        val endExclusive = date.endOfWeekExclusive()

        assertEquals(LocalDate.of(2025, 12, 28), start)
        assertEquals(LocalDate.of(2026, 1, 4), endExclusive)
    }

    @Test
    fun `startOfMonth returns the 1st regardless of which day of the month is given`() {
        assertEquals(LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 1).startOfMonth())
        assertEquals(LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 15).startOfMonth())
        assertEquals(LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 31).startOfMonth())
    }

    @Test
    fun `endOfMonthExclusive is the 1st of the following month`() {
        assertEquals(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 8, 15).endOfMonthExclusive())
    }

    @Test
    fun `endOfMonthExclusive handles a December to January year rollover`() {
        assertEquals(LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 15).endOfMonthExclusive())
    }

    @Test
    fun `endOfMonthExclusive handles a leap-year February correctly`() {
        // 2024 is a leap year - February has 29 days.
        assertEquals(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 2, 10).endOfMonthExclusive())
        // 2025 is not - February has 28.
        assertEquals(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 2, 10).endOfMonthExclusive())
    }

    @Test
    fun `month range never includes a day from the next month`() {
        val start = LocalDate.of(2025, 4, 1).startOfMonth() // April has 30 days
        val endExclusive = LocalDate.of(2025, 4, 1).endOfMonthExclusive()
        val lastDayInRange = endExclusive.minusDays(1)

        assertEquals(30, lastDayInRange.dayOfMonth)
        assertEquals(4, lastDayInRange.monthValue)
    }
}
