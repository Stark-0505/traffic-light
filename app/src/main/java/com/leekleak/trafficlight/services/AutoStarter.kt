package com.leekleak.trafficlight.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.leekleak.trafficlight.database.AppPreferenceRepo
import com.leekleak.trafficlight.model.PermissionManager
import com.leekleak.trafficlight.services.notifications.NotificationService
import com.leekleak.trafficlight.util.launchBroadcastWork
import com.leekleak.trafficlight.widget.forceRefreshWidgets
import com.leekleak.trafficlight.widget.placedWidgetCount
import com.leekleak.trafficlight.widget.startAlarmManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Restores the app's background state after the device boots or the app is updated
 * (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) - see [RestartRestorer]. Both events kill the
 * notification service, and both leave widgets showing the provider's initial layout while
 * their cached "already rendered" state still claims they are current.
 */
class AutoStarter : BroadcastReceiver(), KoinComponent {
    private val permissionManager: PermissionManager by inject()
    private val appPreferenceRepo: AppPreferenceRepo by inject()
    private val applicationScope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pendingResult = goAsync()
        applicationScope.launchBroadcastWork(finish = pendingResult::finish) {
            RestartRestorer(
                updatePermissions = { permissionManager.update() },
                // Only actually worth starting for a user who has the speed notification
                // enabled - NotificationService stops itself right away if it finds nothing
                // to show, but skipping the pointless start-then-immediately-stop round trip
                // is simpler still, and means the service is never created at all for the
                // (likely large) share of users who don't use this feature.
                isSpeedNotificationEnabled = { appPreferenceRepo.notification.first() },
                startNotificationService = { NotificationService.startService(context) },
                // Same idea for the widget refresh alarm and re-render: only worth doing if a
                // widget is actually placed. WidgetReceiver.onDisabled() cancels the alarm
                // once the last widget is removed; this is the restart-time counterpart for
                // "was already removed before this restart".
                placedWidgetCount = { placedWidgetCount(context) },
                startWidgetAlarm = { startAlarmManager(context) },
                forceRefreshWidgets = { forceRefreshWidgets(context) },
            ).restore()
        }
    }
}
