package com.leekleak.trafficlight.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leekleak.trafficlight.database.AppPreferenceRepo
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.database.UsageQuery
import com.leekleak.trafficlight.model.NetworkUsageManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class OverviewVM(
    networkUsageManager: NetworkUsageManager,
    private val appPreferenceRepo: AppPreferenceRepo
) : ViewModel() {
    private val overviewLogic = OverviewLogic(networkUsageManager)
    private val refreshTrigger = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    val query = appPreferenceRepo.overviewDataType.map { UsageQuery(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UsageQuery(DataType.Mobile))

    @OptIn(FlowPreview::class)
    private val refresh = combine(query, refreshTrigger) { q, _ -> q }.debounce(100.milliseconds)
    fun refresh() = refreshTrigger.tryEmit(Unit)

    fun setDataType(dataType: DataType) {
        viewModelScope.launch {
            appPreferenceRepo.setOverviewDataType(dataType)
        }
    }

    // Every figure below comes from one shared load per refresh (see loadOverview) rather
    // than each independently re-querying NetworkStatsManager, so they also represent one
    // consistent snapshot instead of up to six snapshots taken moments apart.
    private val overviewData = refresh.map { overviewLogic.loadOverview(it.dataType) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewData())

    val todayUsage = overviewData.map { it.today }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val yesterdayUsage = overviewData.map { it.yesterday }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val changePercent = overviewData.map { it.changePercent }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val weekTotal = overviewData.map { it.weekTotal }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val weekUsage = overviewData.map { it.weekUsage }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topApps = overviewData.map { it.topApps }
        .distinctUntilChanged { old, new ->
            if (old.size != new.size) return@distinctUntilChanged false
            old.indices.all { i -> old[i].app.uid == new[i].app.uid && old[i].usage.usage1 == new[i].usage.usage1 }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
