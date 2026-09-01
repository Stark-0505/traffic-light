package com.leekleak.trafficlight.ui.navigation

import com.leekleak.trafficlight.model.PermissionManager
import com.leekleak.trafficlight.ui.apps.AppDetail
import com.leekleak.trafficlight.ui.apps.Apps
import com.leekleak.trafficlight.ui.history.History
import com.leekleak.trafficlight.ui.overview.Overview
import com.leekleak.trafficlight.ui.settings.LibraryLicenseScreen
import com.leekleak.trafficlight.ui.settings.NotificationSettingsScreen
import com.leekleak.trafficlight.ui.settings.Settings
import com.leekleak.trafficlight.ui.settings.UsagePermissionRequest
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

@OptIn(KoinExperimentalAPI::class)
val navigationModule = module {

    single {
        val permissionManager: PermissionManager = get()
        permissionManager.update()
        val destination = if (permissionManager.usagePermissionFlow.value) OverviewKey else UsagePermissionRequestKey
        Navigator(startDestination = destination)
    }
    navigation<OverviewKey> { Overview() }
    navigation<HistoryKey> { History() }
    navigation<AppsKey> { Apps() }
    navigation<AppDetailKey> { key -> AppDetail(key.uid) }
    navigation<SettingsKey> { Settings(get()) }
    navigation<UsagePermissionRequestKey> { UsagePermissionRequest() }
    navigation<NotificationSettingsKey> { NotificationSettingsScreen(get()) }
    navigation<LibraryLicenseScreen> { LibraryLicenseScreen(get()) }
}