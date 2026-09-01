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

private val Context.appsPreferences: DataStore<Preferences> by preferencesDataStore(name = "apps_screen")

/** Persists the Apps screen's filter state: network, Day/Week/Month period, and sort order. */
class AppsPreferenceRepo(private val context: Context) {
    private val dataStore get() = context.appsPreferences
    private val data get() = dataStore.data

    val network: Flow<DataType> = data.map { prefs ->
        prefs[NETWORK]?.let { valueOfOrNull<DataType>(it) } ?: DataType.Mobile
    }.distinctUntilChanged()
    suspend fun setNetwork(value: DataType) = dataStore.edit { it[NETWORK] = value.name }

    val grouping: Flow<HistoryGrouping> = data.map { prefs ->
        prefs[GROUPING]?.let { valueOfOrNull<HistoryGrouping>(it) } ?: HistoryGrouping.Month
    }.distinctUntilChanged()
    suspend fun setGrouping(value: HistoryGrouping) = dataStore.edit { it[GROUPING] = value.name }

    val sort: Flow<AppSort> = data.map { prefs ->
        prefs[SORT]?.let { valueOfOrNull<AppSort>(it) } ?: AppSort.Highest
    }.distinctUntilChanged()
    suspend fun setSort(value: AppSort) = dataStore.edit { it[SORT] = value.name }

    private companion object {
        private val NETWORK = stringPreferencesKey("apps_network")
        private val GROUPING = stringPreferencesKey("apps_grouping")
        private val SORT = stringPreferencesKey("apps_sort")
    }
}
