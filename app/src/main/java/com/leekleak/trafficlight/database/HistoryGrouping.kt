package com.leekleak.trafficlight.database

import androidx.annotation.StringRes
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.util.endOfMonthExclusive
import com.leekleak.trafficlight.util.endOfWeekExclusive
import com.leekleak.trafficlight.util.monthLabel
import com.leekleak.trafficlight.util.shortDayLabel
import com.leekleak.trafficlight.util.startOfMonth
import com.leekleak.trafficlight.util.startOfWeek
import com.leekleak.trafficlight.util.weekRangeLabel
import java.time.LocalDate

/**
 * How History/Apps group usage rows. Each case knows how to compute the `i`-th period
 * before an anchor date and how to label it - this is the one place that logic lives, so
 * Day, Week, and Month rows are never generated ad hoc by a screen.
 */
enum class HistoryGrouping(@StringRes val nameRes: Int, val defaultRowCount: Int) {
    Day(R.string.day, 30),
    Week(R.string.week, 12),
    Month(R.string.month, 12);

    /** Returns `[start, endExclusive)` for the period [stepsBack] periods before [anchor]. */
    fun rangeGoingBack(anchor: LocalDate, stepsBack: Int): Pair<LocalDate, LocalDate> = when (this) {
        Day -> {
            val day = anchor.minusDays(stepsBack.toLong())
            day to day.plusDays(1)
        }
        Week -> {
            val weekStart = anchor.startOfWeek().minusWeeks(stepsBack.toLong())
            weekStart to weekStart.endOfWeekExclusive()
        }
        Month -> {
            val monthStart = anchor.startOfMonth().minusMonths(stepsBack.toLong())
            monthStart to monthStart.endOfMonthExclusive()
        }
    }

    /**
     * Locale-formatted label for the period starting at [start], with no notion of
     * "Today"/"Yesterday" (that's app-string-dependent and applied by the UI layer for
     * [Day] rows - see `PeriodRow.label()` in History.kt).
     */
    fun rawLabel(start: LocalDate): String = when (this) {
        Day -> start.shortDayLabel()
        Week -> start.weekRangeLabel()
        Month -> start.monthLabel()
    }
}

enum class PeriodSort(@StringRes val nameRes: Int) {
    Newest(R.string.newest),
    Oldest(R.string.oldest),
    Highest(R.string.highest_usage),
    Lowest(R.string.lowest_usage),
}

enum class AppSort(@StringRes val nameRes: Int) {
    Highest(R.string.highest_usage),
    Lowest(R.string.lowest_usage),
    AToZ(R.string.a_to_z),
}

fun <T> List<T>.sortedByPeriod(sort: PeriodSort, start: (T) -> LocalDate, total: (T) -> Long): List<T> =
    when (sort) {
        PeriodSort.Newest -> sortedByDescending { start(it) }
        PeriodSort.Oldest -> sortedBy { start(it) }
        PeriodSort.Highest -> sortedByDescending { total(it) }
        PeriodSort.Lowest -> sortedBy { total(it) }
    }
