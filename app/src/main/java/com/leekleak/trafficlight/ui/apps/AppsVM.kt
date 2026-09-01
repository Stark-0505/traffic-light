package com.leekleak.trafficlight.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leekleak.trafficlight.database.AppSort
import com.leekleak.trafficlight.database.AppUsage
import com.leekleak.trafficlight.database.AppsPreferenceRepo
import com.leekleak.trafficlight.database.DataDirection
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.database.HistoryGrouping
import com.leekleak.trafficlight.database.UsageQuery
import com.leekleak.trafficlight.model.AppManager.Companion.allApp
import com.leekleak.trafficlight.model.NetworkUsageManager
import com.leekleak.trafficlight.util.toTimestamp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Drives the Apps screen: which apps used data, for one network, over the currently
 * selected Day/Week/Month period (period-to-date, not a scrollable series - Apps answers
 * "who's using data right now", History answers "when was it used").
 */
class AppsVM(
    private val networkUsageManager: NetworkUsageManager,
    private val prefs: AppsPreferenceRepo,
) : ViewModel() {
    private val refreshTrigger = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    private val network = MutableStateFlow(DataType.Mobile)
    private val grouping = MutableStateFlow(HistoryGrouping.Month)
    private val sort = MutableStateFlow(AppSort.Highest)

    val networkFlow = network.asStateFlow()
    val groupingFlow = grouping.asStateFlow()
    val sortFlow = sort.asStateFlow()

    init {
        viewModelScope.launch {
            network.value = prefs.network.first()
            grouping.value = prefs.grouping.first()
            sort.value = prefs.sort.first()
        }
    }

    fun refresh() = refreshTrigger.tryEmit(Unit)

    fun setNetwork(value: DataType) {
        network.value = value
        viewModelScope.launch { prefs.setNetwork(value) }
    }

    fun setGrouping(value: HistoryGrouping) {
        grouping.value = value
        viewModelScope.launch { prefs.setGrouping(value) }
    }

    fun setSort(value: AppSort) {
        sort.value = value
        viewModelScope.launch { prefs.setSort(value) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawApps = combine(network, grouping, refreshTrigger) { n, g, _ -> n to g }
        .flatMapLatest { (n, g) ->
            flow {
                val (start, end) = g.rangeGoingBack(LocalDate.now(), 0)
                emit(
                    networkUsageManager.getAllAppUsage(
                        startStamp = start.toTimestamp(),
                        endStamp = end.toTimestamp(),
                        query1 = UsageQuery(n, DataDirection.Download),
                        query2 = UsageQuery(n, DataDirection.Upload),
                    )
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<AppUsage>())

    val totalUsage = rawApps.map { list -> list.find { it.app == allApp }?.usage?.totalUsage ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val apps = combine(rawApps, sort) { list, s ->
        val withoutTotal = list.filter { it.app != allApp }
        when (s) {
            AppSort.Highest -> withoutTotal.sortedByDescending { it.usage.totalUsage }
            AppSort.Lowest -> withoutTotal.sortedBy { it.usage.totalUsage }
            // Name resolution needs a Context, which isn't available in the ViewModel -
            // left unsorted-by-usage here and alphabetized in the UI layer instead.
            AppSort.AToZ -> withoutTotal
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
