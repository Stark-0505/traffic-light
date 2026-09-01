package com.leekleak.trafficlight.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.leekleak.trafficlight.util.valueOfOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.historyPreferences: DataStore<Preferences> by preferencesDataStore(name = "history")

/**
 * Persists History's filter state: one network at a time (never both crammed together by
 * default), the active Day/Week/Month grouping, and the sort order.
 */
class HistoryPreferenceRepo(private val context: Context) {
    private val dataStore get() = context.historyPreferences
    private val data get() = dataStore.data

    val network: Flow<DataType> = data.map { prefs ->
        prefs[NETWORK]?.let { valueOfOrNull<DataType>(it) } ?: DataType.Mobile
    }.distinctUntilChanged()
    suspend fun setNetwork(value: DataType) = dataStore.edit { it[NETWORK] = value.name }

    val grouping: Flow<HistoryGrouping> = data.map { prefs ->
        prefs[GROUPING]?.let { valueOfOrNull<HistoryGrouping>(it) } ?: HistoryGrouping.Day
    }.distinctUntilChanged()
    suspend fun setGrouping(value: HistoryGrouping) = dataStore.edit { it[GROUPING] = value.name }

    val sort: Flow<PeriodSort> = data.map { prefs ->
        prefs[SORT]?.let { valueOfOrNull<PeriodSort>(it) } ?: PeriodSort.Newest
    }.distinctUntilChanged()
    suspend fun setSort(value: PeriodSort) = dataStore.edit { it[SORT] = value.name }

    private companion object {
        private val NETWORK = stringPreferencesKey("history_network")
        private val GROUPING = stringPreferencesKey("history_grouping")
        private val SORT = stringPreferencesKey("history_sort")
    }
}
