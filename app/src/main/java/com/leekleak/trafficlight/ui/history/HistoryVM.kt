package com.leekleak.trafficlight.ui.history

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leekleak.trafficlight.database.AppUsage
import com.leekleak.trafficlight.database.DataDirection
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.database.HistoryGrouping
import com.leekleak.trafficlight.database.HistoryPreferenceRepo
import com.leekleak.trafficlight.database.HourUsage
import com.leekleak.trafficlight.database.PeriodRow
import com.leekleak.trafficlight.database.PeriodSort
import com.leekleak.trafficlight.database.UsageQuery
import com.leekleak.trafficlight.database.sortedByPeriod
import com.leekleak.trafficlight.model.AppManager
import com.leekleak.trafficlight.model.AppManager.Companion.allApp
import com.leekleak.trafficlight.model.NetworkUsageManager
import com.leekleak.trafficlight.util.startOfMonth
import com.leekleak.trafficlight.util.toTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate

/**
 * Drives the structured History table: one network at a time, grouped by Day/Week/Month,
 * sortable, with an optional inline hourly breakdown for a single expanded Day row.
 *
 * Fetching (network + grouping) and sorting are deliberately separate flows: changing sort
 * order only re-sorts data already in memory, it never re-queries [NetworkUsageManager].
 */
class HistoryVM(
    private val networkUsageManager: NetworkUsageManager,
    private val appManager: AppManager,
    private val prefs: HistoryPreferenceRepo,
) : ViewModel() {
    private val refreshTrigger = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    private val network = MutableStateFlow(DataType.Mobile)
    private val grouping = MutableStateFlow(HistoryGrouping.Day)
    private val sort = MutableStateFlow(PeriodSort.Newest)
    private val expandedRow = MutableStateFlow<LocalDate?>(null)
    // Day alone defaults to 30 rows - loading all of them right away means firing that many
    // parallel period queries for a screen that only ever shows a handful at once. Starting
    // at a smaller visible count and growing it as the user actually scrolls close to the
    // end (see loadMoreIfNeeded) keeps the initial load light without limiting how far back
    // History can still go.
    private val visibleRowCount = MutableStateFlow(INITIAL_ROW_COUNT)

    val networkFlow = network.asStateFlow()
    val groupingFlow = grouping.asStateFlow()
    val sortFlow = sort.asStateFlow()
    val expandedRowFlow = expandedRow.asStateFlow()

    init {
        viewModelScope.launch {
            network.value = prefs.network.first()
            grouping.value = prefs.grouping.first()
            sort.value = prefs.sort.first()
            // Month opens straight into the analytical overview for the current month
            // rather than a flat list the user has to tap into themselves.
            if (grouping.value == HistoryGrouping.Month) expandedRow.value = LocalDate.now().startOfMonth()
        }
    }

    fun refresh() = refreshTrigger.tryEmit(Unit)

    fun setNetwork(value: DataType) {
        network.value = value
        expandedRow.value = if (grouping.value == HistoryGrouping.Month) LocalDate.now().startOfMonth() else null
        visibleRowCount.value = INITIAL_ROW_COUNT
        viewModelScope.launch { prefs.setNetwork(value) }
    }

    fun setGrouping(value: HistoryGrouping) {
        grouping.value = value
        expandedRow.value = if (value == HistoryGrouping.Month) LocalDate.now().startOfMonth() else null
        visibleRowCount.value = INITIAL_ROW_COUNT
        viewModelScope.launch { prefs.setGrouping(value) }
    }

    fun setSort(value: PeriodSort) {
        sort.value = value
        viewModelScope.launch { prefs.setSort(value) }
    }

    fun toggleExpanded(date: LocalDate) {
        expandedRow.value = if (expandedRow.value == date) null else date
    }

    /**
     * Called as the visible list approaches its end (see History's LazyColumn). Grows the
     * fetched row count by one more page, up to the grouping's full historical depth -
     * a no-op once everything up to [HistoryGrouping.defaultRowCount] is already loaded,
     * or if [lastVisibleIndex] isn't actually near the end yet.
     */
    fun loadMoreIfNeeded(lastVisibleIndex: Int) {
        val current = visibleRowCount.value
        if (lastVisibleIndex < current - LOAD_MORE_THRESHOLD) return
        val max = grouping.value.defaultRowCount
        if (current >= max) return
        visibleRowCount.value = (current + ROW_COUNT_PAGE_SIZE).coerceAtMost(max)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawRows: StateFlow<List<PeriodRow>> = combine(network, grouping, visibleRowCount, refreshTrigger) { n, g, count, _ -> Triple(n, g, count) }
        .flatMapLatest { (n, g, count) -> flow { emit(networkUsageManager.periodSeries(n, g, count = count)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rows: StateFlow<List<PeriodRow>> = combine(rawRows, sort) { rows, s ->
        rows.sortedByPeriod(s, { it.start }, { it.totals.total })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val expandedHourly: StateFlow<List<HourUsage>> = combine(expandedRow, network) { date, n -> date to n }
        .flatMapLatest { (date, n) ->
            if (date == null) flowOf(emptyList())
            else flow { emit(networkUsageManager.getAllHourUsage(DateParams(date, false), UsageQuery(n), UsageQuery(DataType.None))) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The analytical breakdown for the expanded Month row: change vs the
     * previous month, a within-month weekly split, and that month's top apps. Only
     * fetched while a Month row is actually expanded, and re-fetched (not re-derived)
     * whenever the expanded row or network changes, since none of this data is otherwise
     * present in [rawRows].
     *
     * The inner flow builder is given an explicit `<MonthlyOverview?>` type argument
     * rather than branching between `flowOf(null)` and a separately-inferred
     * `flow { emit(buildMonthlyOverview(...)) }` - two independently-inferred flows (one
     * from a bare `null` literal, one from a non-null `MonthlyOverview` emit) previously
     * left the compiler nothing solid to unify them against, and it collapsed the
     * `flatMapLatest` result type down to `Flow<Nothing?>` instead of the intended
     * `Flow<MonthlyOverview?>`. A single explicitly-typed flow with two `emit` calls has
     * no such ambiguity: both `null` and `MonthlyOverview` are valid values of the same
     * declared `MonthlyOverview?` stream.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val monthlyOverview: StateFlow<MonthlyOverview?> = combine(expandedRow, grouping, network, rawRows) { date, g, n, currentRows ->
        if (g != HistoryGrouping.Month || date == null) null
        else currentRows.find { it.start == date }?.let { it to n }
    }
        .flatMapLatest { target ->
            flow<MonthlyOverview?> {
                if (target == null) emit(null)
                else emit(buildMonthlyOverview(target.first, target.second))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private suspend fun buildMonthlyOverview(row: PeriodRow, network: DataType): MonthlyOverview = coroutineScope {
        val previousTotalsDeferred = async(Dispatchers.IO) {
            networkUsageManager.periodTotals(network, row.start.minusMonths(1), row.start)
        }
        val weeklyRowsDeferred = async(Dispatchers.IO) {
            networkUsageManager.weeksInMonth(network, row.start, row.endExclusive)
        }
        val topAppsDeferred = async(Dispatchers.IO) {
            networkUsageManager.getAllAppUsage(
                startStamp = row.start.toTimestamp(),
                endStamp = row.endExclusive.toTimestamp(),
                query1 = UsageQuery(network, DataDirection.Download),
                query2 = UsageQuery(network, DataDirection.Upload),
            ).filter { it.app != allApp }
        }

        val previousTotal = previousTotalsDeferred.await().total
        MonthlyOverview(
            monthRow = row,
            // 0 previous-month usage can't produce a meaningful percent change (and is
            // indistinguishable from "no data yet") - null tells the UI to omit the
            // comparison rather than show a nonsensical "+∞%"/"+0%".
            previousMonthTotal = previousTotal.takeIf { it > 0L },
            weeklyRows = weeklyRowsDeferred.await(),
            topApps = topAppsDeferred.await().take(5),
        )
    }

    suspend fun openPackageSettings(activity: Activity?, uid: Int) {
        val app = appManager.getAppForUID(uid)
        if (app.packageName.isNotEmpty()) {
            activity?.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${app.packageName}".toUri()
                )
            )
        }
    }

    fun openApp(activity: Activity?, launchIntent: Intent?) {
        launchIntent?.let {
            try {
                activity?.startActivity(it)
            } catch (e: android.content.ActivityNotFoundException) {
                Timber.e(e, "App not found for launch intent")
            }
        }
    }

    companion object {
        private const val INITIAL_ROW_COUNT = 14
        private const val ROW_COUNT_PAGE_SIZE = 14
        private const val LOAD_MORE_THRESHOLD = 5
    }
}

data class DateParams(val day: LocalDate, val showMonth: Boolean) {
    fun getStartEndDates(): Pair<LocalDate, LocalDate> {
        val start = if (showMonth) { day.withDayOfMonth(1) } else { day }
        val end = if (showMonth) {
            day.withDayOfMonth(1).plusMonths(1).minusDays(1)
        } else {
            day.plusDays(1)
        }
        return Pair(start, end)
    }
}

/**
 * The analytical view for one expanded Month row - see [HistoryVM.buildMonthlyOverview].
 * [topApps] percentages should always be computed against [monthRow]'s own total (the true
 * device-level total for the month), never against the sum of [topApps] itself: app usage
 * can under-count real network usage (system/background traffic NetworkStatsManager doesn't
 * attribute to any app), so that's the only denominator that doesn't silently overstate
 * what each app's share of total usage really was.
 */
data class MonthlyOverview(
    val monthRow: PeriodRow,
    val previousMonthTotal: Long?,
    val weeklyRows: List<PeriodRow>,
    val topApps: List<AppUsage>,
)
