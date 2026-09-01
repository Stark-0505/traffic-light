package com.leekleak.trafficlight.ui.overview

import com.leekleak.trafficlight.charts.model.BarData
import com.leekleak.trafficlight.database.AppUsage
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.database.UsageQuery
import com.leekleak.trafficlight.model.AppManager.Companion.allApp
import com.leekleak.trafficlight.model.NetworkUsageManager
import com.leekleak.trafficlight.util.toTimestamp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** Everything the Overview screen shows for one [DataType], loaded together - see [OverviewLogic.loadOverview]. */
data class OverviewData(
    val today: Long = 0L,
    val yesterday: Long = 0L,
    val changePercent: Int? = null,
    val weekTotal: Long = 0L,
    val weekUsage: List<BarData> = emptyList(),
    val topApps: List<AppUsage> = emptyList(),
)

/**
 * Backs the Overview summary: today's usage, a same-network yesterday/day-before
 * comparison, the weekly trend, and a top-apps preview. Everything here is scoped to a
 * single [DataType] - Overview never blends Mobile and Wi-Fi into one figure.
 *
 * Today/Yesterday/day-before/This-week only ever need a device-wide total, never a
 * per-app breakdown, so they go through [NetworkUsageManager.periodTotals] - a single
 * device-aggregated query per period - rather than [NetworkUsageManager.totalDayUsage],
 * which additionally runs and discards a per-UID breakdown it doesn't need here.
 */
class OverviewLogic(val networkUsageManager: NetworkUsageManager) {
    /**
     * Loads every figure Overview shows for [dataType] as one coordinated pass, so:
     * - yesterday's total is fetched once and reused for the change-percent comparison,
     *   instead of being queried twice;
     * - the week total is the sum of the same daily bars [weekUsage] already fetches,
     *   instead of a separate query covering the same seven days again;
     * - the remaining independent queries (today, day-before, top apps) still run
     *   concurrently, so consolidating them doesn't add latency.
     */
    suspend fun loadOverview(dataType: DataType): OverviewData = coroutineScope {
        val todayDeferred = async { getTodayUsage(dataType) }
        val yesterdayDeferred = async { getYesterdayUsage(dataType) }
        val weekUsageDeferred = async { getWeekUsage(dataType) }
        val topAppsDeferred = async { getTopAppUsage(dataType) }

        val yesterday = yesterdayDeferred.await()
        val weekUsage = weekUsageDeferred.await()

        OverviewData(
            today = todayDeferred.await(),
            yesterday = yesterday,
            changePercent = getChangePercent(dataType, yesterday),
            weekTotal = weekUsage.sumOf { it.y1 + it.y2 },
            weekUsage = weekUsage,
            topApps = topAppsDeferred.await(),
        )
    }

    suspend fun getTodayUsage(dataType: DataType): Long =
        networkUsageManager.periodTotals(dataType, LocalDate.now(), LocalDate.now().plusDays(1)).total

    suspend fun getYesterdayUsage(dataType: DataType): Long =
        networkUsageManager.periodTotals(dataType, LocalDate.now().minusDays(1), LocalDate.now()).total

    /**
     * Yesterday vs the day before, as a whole-percent change. Null when there's nothing
     * to compare against. Takes yesterday's total as a parameter rather than re-fetching
     * it, since callers already have it from [getYesterdayUsage] - Overview shows both
     * figures side by side, so there's no reason to query the same day's total twice.
     */
    suspend fun getChangePercent(dataType: DataType, yesterday: Long): Int? {
        val dayBefore = networkUsageManager.periodTotals(dataType, LocalDate.now().minusDays(2), LocalDate.now().minusDays(1)).total
        if (dayBefore == 0L) return null
        return (((yesterday - dayBefore).toDouble() / dayBefore) * 100.0).roundToInt()
    }

    suspend fun getWeekUsage(dataType: DataType): List<BarData> = networkUsageManager.getWeekUsage(null, dataType)

    suspend fun getTopAppUsage(dataType: DataType): List<AppUsage> {
        val query = UsageQuery(dataType)
        val todayUsage = networkUsageManager.getAllAppUsage(
            startStamp = LocalDate.now().toTimestamp(),
            endStamp = LocalDateTime.now().toTimestamp(),
            query1 = query,
            query2 = UsageQuery(DataType.None),
        )
        return todayUsage.filter { it.app != allApp }.take(3)
    }
}
