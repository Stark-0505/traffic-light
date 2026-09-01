package com.leekleak.trafficlight.database

import java.time.LocalDate

/**
 * Download/upload totals for one period (a day, a week, a month...). Lives alongside
 * [HistoryGrouping] and [PeriodSort] rather than in the `model` package: it's a plain
 * data-layer shape produced by [com.leekleak.trafficlight.model.NetworkUsageManager] and
 * consumed by History's UI/ViewModel layer, and keeping it next to the grouping/sorting
 * logic it's built for is what [sortedByPeriod] and `PeriodRow.label()`-style UI helpers
 * already assume.
 */
data class PeriodTotals(val download: Long = 0L, val upload: Long = 0L) {
    val total: Long get() = download + upload
}

/** One Day/Week/Month row in History - see [HistoryGrouping.rangeGoingBack]. */
data class PeriodRow(
    val start: LocalDate,
    /** Exclusive - i.e. the period is `[start, endExclusive)`. */
    val endExclusive: LocalDate,
    val totals: PeriodTotals,
)
