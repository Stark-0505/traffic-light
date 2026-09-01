package com.leekleak.trafficlight.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.database.HistoryGrouping
import com.leekleak.trafficlight.model.AppManager
import com.leekleak.trafficlight.model.AppPeriodRow
import com.leekleak.trafficlight.model.DataUID
import com.leekleak.trafficlight.model.NetworkUsageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the per-app detail screen: total usage plus a Day/Week/Month breakdown for one
 * app, on one network, reusing the same [HistoryGrouping] the History screen uses so the
 * two screens can never disagree about what a "week" or "month" means.
 */
class AppDetailVM(
    private val networkUsageManager: NetworkUsageManager,
    private val appManager: AppManager,
) : ViewModel() {
    private val uid = MutableStateFlow<Int?>(null)
    private val network = MutableStateFlow(DataType.Mobile)
    private val grouping = MutableStateFlow(HistoryGrouping.Day)
    private val refreshTrigger = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    val networkFlow = network.asStateFlow()
    val groupingFlow = grouping.asStateFlow()

    private val _app = MutableStateFlow<DataUID?>(null)
    val app: StateFlow<DataUID?> = _app.asStateFlow()

    fun setUid(value: Int) {
        uid.value = value
        viewModelScope.launch { _app.value = appManager.getAppForUID(value) }
    }
    fun setNetwork(value: DataType) { network.value = value }
    fun setGrouping(value: HistoryGrouping) { grouping.value = value }
    fun refresh() = refreshTrigger.tryEmit(Unit)

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<AppPeriodRow>> =
        combine(uid, network, grouping, refreshTrigger) { u, n, g, _ -> Triple(u, n, g) }
            .flatMapLatest { (u, n, g) ->
                if (u == null) flow { emit(emptyList()) }
                else flow {
                    val resolvedApp = appManager.getAppForUID(u)
                    emit(networkUsageManager.appPeriodSeries(n, resolvedApp, g))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Total for the current (most recent) period - the headline figure at the top of the screen. */
    val currentPeriodTotal: StateFlow<Long> = rows.map { it.firstOrNull()?.totals?.total ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
}
