package com.leekleak.trafficlight.model

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import com.leekleak.trafficlight.charts.model.BarData
import com.leekleak.trafficlight.database.AppUsage
import com.leekleak.trafficlight.database.DataDirection
import com.leekleak.trafficlight.database.DataDirection.Bidirectional
import com.leekleak.trafficlight.database.DataDirection.Download
import com.leekleak.trafficlight.database.DataDirection.Upload
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.database.DayUsage
import com.leekleak.trafficlight.database.HistoryGrouping
import com.leekleak.trafficlight.database.HourUsage
import com.leekleak.trafficlight.database.PeriodRow
import com.leekleak.trafficlight.database.PeriodTotals
import com.leekleak.trafficlight.database.UsageQuery
import com.leekleak.trafficlight.model.AppManager.Companion.allApp
import com.leekleak.trafficlight.model.AppManager.Companion.otherUsersApp
import com.leekleak.trafficlight.model.AppManager.Companion.specialUIDs
import com.leekleak.trafficlight.ui.history.DateParams
import com.leekleak.trafficlight.util.endOfWeekExclusive
import com.leekleak.trafficlight.util.fromTimestamp
import com.leekleak.trafficlight.util.getName
import com.leekleak.trafficlight.util.overlapRatio
import com.leekleak.trafficlight.util.startOfWeek
import com.leekleak.trafficlight.util.toTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

data class UsageData(
    val upload: Long = 0,
    val download: Long = 0,
    val uid: Int? = null,
    val start: LocalDateTime = LocalDateTime.now(),
    val end: LocalDateTime = LocalDateTime.now()
) {
    val total: Long
        get() = upload + download

    fun forDirection(dataDirection: DataDirection): Long = when(dataDirection) {
        Upload -> upload
        Download -> download
        Bidirectional -> upload + download
    }
}

class NetworkUsageManager(
    private var networkStatsManager: NetworkStatsManager,
    private val appManager: AppManager,
) {
    suspend fun totalDayUsage(
        query: UsageQuery,
        startDate: LocalDate,
        subscriberId: String? = null,
        endDate: LocalDate = startDate.plusDays(1),
    ): Long {
        val startStamp = startDate.toTimestamp()
        val endStamp = endDate.toTimestamp()
        return getNetworkDataForType(startStamp, endStamp, subscriberId, query.dataType).sumOf {
            if (it.uid == query.dataUID.uid || query.dataUID.uidQuery == null) {
                it.forDirection(query.dataDirection)
            } else {
                0L
            }
        }
    }

    suspend fun getNetworkDataForType(
        startStamp: Long,
        endStamp: Long,
        subscriberId: String?,
        type: DataType
    ): List<UsageData> = withContext(Dispatchers.IO) {
        val queryIndex = type.queryIndex ?: return@withContext listOf()

        val bucketsDeferred = async {
            val result = mutableListOf<UsageData>()
            networkStatsManager.querySummary(queryIndex, subscriberId, startStamp, endStamp).use { summary ->
                val map = mutableMapOf<Int, UsageData>()
                while (summary.hasNextBucket()) {
                    val bucket = NetworkStats.Bucket()
                    summary.getNextBucket(bucket)
                    map.merge(bucket.uid, UsageData(bucket.txBytes, bucket.rxBytes, bucket.uid)) { old, new ->
                        old.copy(upload = old.upload + new.upload, download = old.download + new.download)
                    }
                }
                result.addAll(map.values)
            }
            result
        }

        val deviceTotalDeferred = async {
            try {
                networkStatsManager.querySummaryForDevice(queryIndex, subscriberId, startStamp, endStamp)
            } catch (e: SecurityException) {
                Timber.e(e)
                null
            }
        }

        val buckets = bucketsDeferred.await().toMutableList()
        val deviceTotal = deviceTotalDeferred.await() ?: return@withContext emptyList()

        // Reconcile with device total to include other users/Secure Folder
        val diffUpload = maxOf(0L, deviceTotal.txBytes - buckets.sumOf { it.upload })
        val diffDownload = maxOf(0L, deviceTotal.rxBytes - buckets.sumOf { it.download })

        if (diffUpload > 0 || diffDownload > 0) {
            val otherUsersUsage = buckets.find { it.uid == otherUsersApp.uid }
            if (otherUsersUsage != null) {
                val index = buckets.indexOf(otherUsersUsage)
                buckets[index] = otherUsersUsage.copy(
                    upload = otherUsersUsage.upload + diffUpload,
                    download = otherUsersUsage.download + diffDownload
                )
            } else {
                buckets.add(UsageData(diffUpload, diffDownload, otherUsersApp.uid))
            }
        }
        buckets
    }

    suspend fun getNetworkDataForTypeHourly(
        startStamp: Long,
        endStamp: Long,
        subscriberId: String?,
        type: DataType,
        uid: Int?
    ): List<UsageData> = withContext(Dispatchers.IO) {
        val typeQuery = type.queryIndex ?: return@withContext listOf()
        val map = mutableMapOf<LocalDateTime, UsageData>()
        if (uid != null) {
            val summary = networkStatsManager.queryDetailsForUid(typeQuery, subscriberId, startStamp, endStamp, uid)
            while (summary.hasNextBucket()) {
                val bucket = NetworkStats.Bucket()
                summary.getNextBucket(bucket)
                val end = fromTimestamp(bucket.endTimeStamp)
                val start = fromTimestamp(bucket.startTimeStamp)

                map.merge(start, UsageData(bucket.txBytes, bucket.rxBytes, start = start, end = end))
                { old, new ->
                    old.copy(
                        upload = old.upload + new.upload,
                        download = old.download + new.download
                    )
                }
            }
            summary.close()
        } else {
            val hours2 = 1000 * 60 * 60 * 2L
            coroutineScope {
                (startStamp..<endStamp step hours2).map { i ->
                    async(Dispatchers.IO) {
                        val bucket = networkStatsManager.querySummaryForDevice(typeQuery, subscriberId, i, i + hours2)
                        val end = fromTimestamp(bucket.endTimeStamp)
                        val start = fromTimestamp(bucket.startTimeStamp)
                        UsageData(bucket.txBytes, bucket.rxBytes, start = start, end = end)
                    }
                }.awaitAll()
            }.forEach { usage ->
                map.merge(usage.start, usage) { old, new ->
                    old.copy(
                        upload = old.upload + new.upload,
                        download = old.download + new.download
                    )
                }
            }
        }
        return@withContext map.values.toList()
    }

    suspend fun getAllAppUsage(
        startStamp: Long,
        endStamp: Long,
        query1: UsageQuery,
        query2: UsageQuery,
        subscriberId: String? = null
    ): List<AppUsage> {
        val usage1 = getNetworkDataForType(startStamp, endStamp, subscriberId, query1.dataType)
        val usage2 = getNetworkDataForType(startStamp, endStamp, subscriberId, query2.dataType)

        val uids = usage1.map { it.uid }.union(usage2.map { it.uid }).union(specialUIDs).filterNotNull()

        // getNetworkDataForType() already merges bucket rows down to one UsageData per uid,
        // so indexing both lists once here (O(n)) and looking up by key inside the loop
        // below replaces what would otherwise be an O(n²) linear .find() per uid across
        // every installed app that has any usage.
        val usage1ByUid = usage1.associateBy { it.uid }
        val usage2ByUid = usage2.associateBy { it.uid }

        val list = uids.map { uid ->
            val uid1 = usage1ByUid[uid] ?: UsageData()
            val uid2 = usage2ByUid[uid] ?: UsageData()
            AppUsage(
                app = appManager.getAppForUID(uid),
                usage = DayUsage(
                    usage1 = uid1.forDirection(query1.dataDirection),
                    usage2 = uid2.forDirection(query2.dataDirection)
                ),
            )
        }.toMutableList()

        val totalUsage = DayUsage(
            usage1 = list.sumOf { it.usage.usage1 },
            usage2 = list.sumOf { it.usage.usage2 }
        )

        list.sortByDescending { it.usage.totalUsage }
        list.add(0, AppUsage(
            app = allApp,
            usage = totalUsage
        ))
        list.removeAll { it.usage.totalUsage == 0L }
        return list.distinctBy { it.app.uid }.toList()
    }

    suspend fun getAllHourUsage(
        dateParams: DateParams,
        query1: UsageQuery,
        query2: UsageQuery
    ): List<HourUsage> {
        val (startDate, endDate) = dateParams.getStartEndDates()
        val startTime = startDate.atStartOfDay().toTimestamp()
        val endTime = endDate.atStartOfDay().toTimestamp()
        val oneHourMilli = 60 * 60 * 1000L
        val twoHoursMilli = oneHourMilli * 2

        val usage1 = getNetworkDataForTypeHourly(startTime, endTime, null, query1.dataType, query1.dataUID.uidQuery)
        val usage2 = getNetworkDataForTypeHourly(startTime, endTime, null, query2.dataType, query2.dataUID.uidQuery)
        val slots = 0..(endTime - startTime) step twoHoursMilli
        val map1 = slots.associateWith { 0L }.toMutableMap()
        val map2 = slots.associateWith { 0L }.toMutableMap()

        for ((usageList, query, map) in listOf(
            Triple(usage1, query1, map1),
            Triple(usage2, query2, map2),
        )) {
            usageList.forEach { usage ->
                val stampStart = usage.start.toTimestamp() - startTime
                val stampEnd = usage.end.toTimestamp() - startTime
                val bucketRange = stampStart..stampEnd

                for (slot in slots) {
                    val mapRange = slot..(slot+twoHoursMilli)
                    val overlap = overlapRatio(bucketRange, mapRange)
                    map[slot] = map.getValue(slot) + (usage.forDirection(query.dataDirection) * overlap).toLong()
                }
            }
        }

        val start = fromTimestamp(startTime)
        val result = (0..22 step 2).map { i ->
            HourUsage(
                start = start.withHour(i),
                end = start.withHour(i).plusHours(2),
                usage = DayUsage(
                    date = dateParams.day,
                    usage1 = map1.filter { it.key % (12 * twoHoursMilli) == i * oneHourMilli }.values.sum(),
                    usage2 = map2.filter { it.key % (12 * twoHoursMilli) == i * oneHourMilli }.values.sum()
                ),
            )
        }

        return result
    }

    suspend fun getWeekUsage(subscriberId: String?, dataType: DataType = DataType.Mobile): List<BarData> {
        val field = WeekFields.of(Locale.getDefault())
        val firstDay = field.firstDayOfWeek
        val data: MutableList<BarData> = MutableList(7) { i ->
            val x = firstDay.plus(i.toLong()).getName(TextStyle.SHORT_STANDALONE)
            BarData(x, 0, 0)
        }
        val now = LocalDate.now()
        val daysPassed = now.get(field.dayOfWeek()) - 1

        coroutineScope {
            (0..daysPassed).map { i ->
                async {
                    val day = now.minusDays(daysPassed.toLong() - i)
                    val isWifi = dataType == DataType.Wifi
                    // Same total-only case periodTotals() exists for: no per-app
                    // breakdown is used here, just each day's aggregate.
                    val usage = periodTotals(dataType, day, day.plusDays(1), subscriberId).total
                    data[i] = data[i].copy(y1 = if (isWifi) 0 else usage, y2 = if (isWifi) usage else 0)
                }
            }.awaitAll()
        }
        return data.toList()
    }

    suspend fun queryDetails(queryIndex: Int, subscriberId: String?, startStamp: Long, endStamp: Long): NetworkStats = withContext(Dispatchers.IO) {
        networkStatsManager.queryDetails(queryIndex, subscriberId, startStamp, endStamp)
    }

    /**
     * Download/upload totals for one network type over an arbitrary `[startDate, endDate)`
     * range, aggregated device-side by [NetworkStatsManager] in a single
     * [querySummaryForDevice] call - this is the primitive every History/Apps grouping
     * (Day/Week/Month) row is built from.
     *
     * This intentionally does NOT go through [getNetworkDataForType]: that also runs a
     * per-UID [NetworkStatsManager.querySummary] and reconciles it against the device
     * total to attribute a UID to "other users'" usage - work that only matters when the
     * caller needs a per-app breakdown. A pure total is already exactly what
     * [querySummaryForDevice] reports (per-UID reconciliation exists specifically to make
     * the per-UID sum match this device total, not the other way around), so callers that
     * only want the total - every History/Apps period row - skip straight to it.
     */
    suspend fun periodTotals(
        dataType: DataType,
        startDate: LocalDate,
        endDate: LocalDate,
        subscriberId: String? = null,
    ): PeriodTotals = withContext(Dispatchers.IO) {
        val queryIndex = dataType.queryIndex ?: return@withContext PeriodTotals()
        val startStamp = startDate.toTimestamp()
        val endStamp = endDate.toTimestamp()
        try {
            val deviceTotal = networkStatsManager.querySummaryForDevice(queryIndex, subscriberId, startStamp, endStamp)
            PeriodTotals(download = deviceTotal.rxBytes, upload = deviceTotal.txBytes)
        } catch (e: SecurityException) {
            Timber.e(e)
            PeriodTotals()
        }
    }

    /**
     * A row per Day/Week/Month period going back from [anchor], each with its own
     * device-aggregated totals fetched in parallel. [count] controls how many rows are
     * produced (i.e. how far back History/Apps scrolls).
     */
    suspend fun periodSeries(
        dataType: DataType,
        grouping: HistoryGrouping,
        anchor: LocalDate = LocalDate.now(),
        count: Int = grouping.defaultRowCount,
    ): List<PeriodRow> = coroutineScope {
        (0 until count).map { i ->
            async(Dispatchers.IO) {
                val (start, endExclusive) = grouping.rangeGoingBack(anchor, i)
                val totals = periodTotals(dataType, start, endExclusive)
                PeriodRow(start = start, endExclusive = endExclusive, totals = totals)
            }
        }.awaitAll()
    }

    /**
     * Same idea as [periodSeries] but scoped to a single app (or special UID) via
     * [dataUID]. Download and Upload for that UID come from one [getNetworkDataForType]
     * call - it already returns both directions per UID in a single pass - rather than
     * two separate [totalDayUsage] calls (one per direction), each of which would run its
     * own full per-UID query.
     */
    suspend fun appPeriodSeries(
        dataType: DataType,
        dataUID: DataUID,
        grouping: HistoryGrouping,
        anchor: LocalDate = LocalDate.now(),
        count: Int = grouping.defaultRowCount,
    ): List<AppPeriodRow> = coroutineScope {
        (0 until count).map { i ->
            async(Dispatchers.IO) {
                val (start, endExclusive) = grouping.rangeGoingBack(anchor, i)
                val usage = getNetworkDataForType(start.toTimestamp(), endExclusive.toTimestamp(), null, dataType)
                val totals = if (dataUID.uidQuery == null) {
                    // The "all apps" sentinel UID - total across every UID, same as periodTotals.
                    PeriodTotals(download = usage.sumOf { it.download }, upload = usage.sumOf { it.upload })
                } else {
                    val uidUsage = usage.find { it.uid == dataUID.uid }
                    PeriodTotals(download = uidUsage?.download ?: 0L, upload = uidUsage?.upload ?: 0L)
                }
                AppPeriodRow(start = start, endExclusive = endExclusive, totals = totals)
            }
        }.awaitAll()
    }

    /**
     * Calendar weeks overlapping `[monthStart, monthEndExclusive)`, each clipped to stay
     * inside the month - a week that spans a month boundary only counts the days that
     * actually fall within this month, so the rows sum back to the month total. Powers
     * History's Month analytical breakdown only; the app's one definition of "a week" stays
     * [HistoryGrouping.Week] + [HistoryGrouping.rangeGoingBack], so this can never disagree
     * with it about where a week starts.
     */
    suspend fun weeksInMonth(
        dataType: DataType,
        monthStart: LocalDate,
        monthEndExclusive: LocalDate,
    ): List<PeriodRow> = coroutineScope {
        val weekStarts = generateSequence(monthStart.startOfWeek()) { it.plusWeeks(1) }
            .takeWhile { it < monthEndExclusive }
            .toList()
        weekStarts.map { weekStart ->
            async(Dispatchers.IO) {
                val clippedStart = maxOf(weekStart, monthStart)
                val clippedEnd = minOf(weekStart.endOfWeekExclusive(), monthEndExclusive)
                val totals = periodTotals(dataType, clippedStart, clippedEnd)
                PeriodRow(start = clippedStart, endExclusive = clippedEnd, totals = totals)
            }
        }.awaitAll()
    }
}

data class AppPeriodRow(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val totals: PeriodTotals,
)