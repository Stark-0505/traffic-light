package com.leekleak.trafficlight.database

import org.koin.dsl.module

/**
 * No local database: every screen reads live/historical usage straight from Android's
 * [android.app.usage.NetworkStatsManager] via [NetworkUsageManager][com.leekleak.trafficlight.model.NetworkUsageManager].
 * The only persisted state is user preferences (DataStore), below.
 */
val databaseModule = module {
    single { AppPreferenceRepo(get()) }
    single { HistoryPreferenceRepo(get()) }
    single { AppsPreferenceRepo(get()) }
}
