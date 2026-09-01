package com.leekleak.trafficlight.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Main screens
 */

@Serializable
data object OverviewKey : NavKey

@Serializable
data object HistoryKey : NavKey

@Serializable
data object AppsKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object UsagePermissionRequestKey : NavKey

val mainScreens = listOf(OverviewKey, HistoryKey, AppsKey)

/** Per-app usage detail, opened from the Apps screen. */
@Serializable
data class AppDetailKey(val uid: Int) : NavKey

/**
 * Settings
 */
@Serializable
data object NotificationSettingsKey : NavKey
@Serializable
data object LibraryLicenseScreen : NavKey
