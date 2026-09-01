package com.leekleak.trafficlight.util

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Single source of truth for week boundaries. Every screen that groups usage by week
 * (History, Apps, per-app detail, Overview's weekly trend) must derive its boundaries
 * from this instead of recomputing [WeekFields] locally, or the app risks using
 * Sunday-start in one place and Monday-start in another - producing rows whose ranges
 * don't line up and totals that don't reconcile between screens.
 */
val weekFields: WeekFields = WeekFields.of(Locale.getDefault())

/** Inclusive first day of the locale week containing this date. */
fun LocalDate.startOfWeek(): LocalDate = with(weekFields.dayOfWeek(), 1)

/** Exclusive end (first day of the *next* week) - use as the end bound of a `[start, end)` range. */
fun LocalDate.endOfWeekExclusive(): LocalDate = startOfWeek().plusWeeks(1)

/** Inclusive first day of the calendar month containing this date. */
fun LocalDate.startOfMonth(): LocalDate = withDayOfMonth(1)

/** Exclusive end (first day of the *next* month) - use as the end bound of a `[start, end)` range. */
fun LocalDate.endOfMonthExclusive(): LocalDate = YearMonth.from(this).plusMonths(1).atDay(1)

/** "Aug 25" / "Aug" depending on whether the year needs disambiguating. */
fun LocalDate.shortDayLabel(): String {
    val month = month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    return "$month $dayOfMonth"
}

/** "Aug 25 - 31" or "Aug 25 - Sep 1" when the week crosses a month boundary. */
fun LocalDate.weekRangeLabel(): String {
    val start = startOfWeek()
    val end = endOfWeekExclusive().minusDays(1)
    val startMonth = start.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    return if (start.month == end.month) {
        "$startMonth ${start.dayOfMonth} - ${end.dayOfMonth}"
    } else {
        val endMonth = end.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        "$startMonth ${start.dayOfMonth} - $endMonth ${end.dayOfMonth}"
    }
}

/** "August 2026". */
fun LocalDate.monthLabel(): String {
    val month = month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    return "$month $year"
}
