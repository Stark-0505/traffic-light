package com.leekleak.trafficlight.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leekleak.trafficlight.BuildConfig
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.database.AppPreferenceRepo
import com.leekleak.trafficlight.model.PermissionManager
import com.leekleak.trafficlight.ui.components.BackAction
import com.leekleak.trafficlight.ui.components.HazeScaffold
import com.leekleak.trafficlight.ui.navigation.LibraryLicenseScreen
import com.leekleak.trafficlight.ui.navigation.Navigator
import com.leekleak.trafficlight.ui.navigation.NotificationSettingsKey
import com.leekleak.trafficlight.ui.theme.Theme
import com.leekleak.trafficlight.ui.theme.googleSans
import com.leekleak.trafficlight.util.CategoryTitleSmallText
import com.leekleak.trafficlight.util.SlideAnimatedVisibility
import com.leekleak.trafficlight.util.openLink
import com.leekleak.trafficlight.util.px
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Grouped into five calm, logically-scoped sections (Permissions, Notifications,
 * Appearance, Data, About) rather than one long list of independent cards - the same
 * information-first hierarchy as the rest of the app.
 */
@Composable
fun Settings(navigator: Navigator) {
    HazeScaffold(
        title = stringResource(R.string.settings),
        verticalArrangement = Arrangement.Top,
        backAction = BackAction.Normal(navigator),
    ) {
        PermissionsSection()
        NotificationsSection()
        AppearanceSection()
        DataSection()
        AboutSection()
    }
}

@Composable
private fun PermissionsSection() {
    val permissionManager: PermissionManager = koinInject()
    val activity = LocalActivity.current
    val backgroundPermission by permissionManager.backgroundPermissionFlow.collectAsStateWithLifecycle()
    val notificationPermission by permissionManager.notificationPermissionFlow.collectAsStateWithLifecycle()
    val notificationPermissionCallback =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    SlideAnimatedVisibility(!backgroundPermission || !notificationPermission) {
        Column {
            CategoryTitleSmallText(stringResource(R.string.permissions))
            SlideAnimatedVisibility(!notificationPermission) {
                PermissionCard(
                    title = stringResource(R.string.notification_permission),
                    description = stringResource(R.string.allow_app_to_send_notifications),
                    icon = painterResource(R.drawable.notification),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionCallback.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }
            SlideAnimatedVisibility(!backgroundPermission) {
                PermissionCard(
                    title = stringResource(R.string.battery_optimization),
                    description = stringResource(R.string.battery_optimization_description),
                    icon = painterResource(R.drawable.battery),
                    onClick = { permissionManager.askBackgroundPermission(activity) }
                )
            }
        }
    }
}

@Composable
private fun NotificationsSection() {
    val viewModel = koinViewModel<SettingsVM>()
    val appPreferenceRepo: AppPreferenceRepo = koinInject()
    val permissionManager: PermissionManager = koinInject()
    val navigator: Navigator = koinInject()
    val scope = rememberCoroutineScope()

    CategoryTitleSmallText(stringResource(R.string.notifications))
    val notification by viewModel.notification.collectAsStateWithLifecycle()
    val notificationPermission by permissionManager.notificationPermissionFlow.collectAsStateWithLifecycle()

    SettingsGroup {
        SwitchPreference(
            title = stringResource(R.string.speed_notification),
            summary = stringResource(R.string.notification_description),
            icon = painterResource(R.drawable.speed_notification),
            value = notification,
            enabled = notificationPermission,
            onValueChanged = { scope.launch { appPreferenceRepo.setNotification(it) } },
        )

        SlideAnimatedVisibility(notification) {
            NavigatePreference(
                title = stringResource(R.string.advanced_settings),
                icon = painterResource(R.drawable.notification_settings),
                enabled = notificationPermission,
                onClick = { navigator.goTo(NotificationSettingsKey) }
            )
        }
    }
}

@Composable
private fun AppearanceSection() {
    val appPreferenceRepo: AppPreferenceRepo = koinInject()
    val scope = rememberCoroutineScope()

    CategoryTitleSmallText(stringResource(R.string.appearance))
    val theme by appPreferenceRepo.theme.collectAsState(Theme.AutoMaterial)
    val blur by appPreferenceRepo.blur.collectAsState(true)
    val scroll = rememberScrollState(0)

    val panelWidth = 272.dp.px.toInt()
    LaunchedEffect(theme) {
        scroll.animateScrollTo(panelWidth * (theme.ordinal / 3), tween())
    }
    SettingsGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemePreferenceContainer(theme, true) { scope.launch { appPreferenceRepo.setTheme(it) } }
            ThemePreferenceContainer(theme, false) { scope.launch { appPreferenceRepo.setTheme(it) } }
        }
        SwitchPreference(
            title = stringResource(R.string.blur_effects),
            icon = painterResource(R.drawable.blur_on),
            value = blur,
            onValueChanged = { scope.launch { appPreferenceRepo.setBlur(it) } },
        )
    }
}

@Composable
private fun DataSection() {
    val appPreferenceRepo: AppPreferenceRepo = koinInject()
    val scope = rememberCoroutineScope()

    CategoryTitleSmallText(stringResource(R.string.data))
    val speedMetric by appPreferenceRepo.speedMetric.collectAsState(false)
    val sizeMetric by appPreferenceRepo.sizeMetric.collectAsState(false)
    val values = listOf(
        true to stringResource(R.string.base_1000),
        false to stringResource(R.string.base_1024)
    )
    SettingsGroup {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DialogItemSelectPreference(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.speed_unit),
                icon = painterResource(R.drawable.network_check),
                value = speedMetric,
                values = values,
                onValueChanged = { scope.launch { appPreferenceRepo.setSpeedMetric(it) } },
            )
            DialogItemSelectPreference(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.size_unit),
                icon = painterResource(R.drawable.database),
                value = sizeMetric,
                values = values,
                onValueChanged = { scope.launch { appPreferenceRepo.setSizeMetric(it) } },
            )
        }
    }
}

@Composable
private fun AboutSection() {
    val appPreferenceRepo: AppPreferenceRepo = koinInject()
    val navigator: Navigator = koinInject()
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()

    CategoryTitleSmallText(stringResource(R.string.about))
    val ads by appPreferenceRepo.ads.collectAsState(false)
    SettingsGroup {
        SwitchPreference(
            title = stringResource(R.string.supporter_ads),
            summary = stringResource(
                if (BuildConfig.ADS) R.string.supporter_ads_description
                else R.string.supporter_ads_disabled
            ),
            icon = painterResource(R.drawable.celebration),
            value = ads,
            enabled = BuildConfig.ADS,
            onValueChanged = { scope.launch { appPreferenceRepo.setAds(it) } },
        )
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NavigatePreference(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.donate),
                icon = painterResource(R.drawable.donate),
                onClick = { openLink(activity, "https://github.com/sponsors/leekleak") },
                showControl = false
            )
            NavigatePreferenceIcon(
                icon = painterResource(R.drawable.github),
                contentDescription = stringResource(R.string.github),
                onClick = { openLink(activity, "https://github.com/leekleak/traffic-light") },
            )
            NavigatePreferenceIcon(
                icon = painterResource(R.drawable.gavel),
                contentDescription = stringResource(R.string.licenses),
                onClick = { navigator.goTo(LibraryLicenseScreen) },
            )
        }
    }

    val fontFamily = remember { googleSans(weight = 600f, roundness = 100f) }
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.6f)
            .padding(vertical = 4.dp),
        fontFamily = fontFamily,
        text = stringResource(R.string.version_short, BuildConfig.VERSION_NAME),
        textAlign = TextAlign.Center
    )
}
