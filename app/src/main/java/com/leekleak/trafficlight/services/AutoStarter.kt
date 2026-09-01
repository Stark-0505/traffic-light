package com.leekleak.trafficlight.services

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.leekleak.trafficlight.database.AppPreferenceRepo
import com.leekleak.trafficlight.model.PermissionManager
import com.leekleak.trafficlight.services.notifications.NotificationService
import com.leekleak.trafficlight.widget.WidgetReceiver
import com.leekleak.trafficlight.widget.startAlarmManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class AutoStarter : BroadcastReceiver(), KoinComponent {
    private val permissionManager: PermissionManager by inject()
    private val appPreferenceRepo: AppPreferenceRepo by inject()
    private val applicationScope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            applicationScope.launch {
                try {
                    permissionManager.update()

                    // Only actually worth starting for a user who has the speed
                    // notification enabled - NotificationService now stops itself right
                    // away if it finds nothing to show, but skipping the pointless
                    // start-then-immediately-stop round trip on every single boot is
                    // simpler still, and means the service is never created at all for
                    // the (likely large) share of users who don't use this feature.
                    if (appPreferenceRepo.notification.first()) {
                        NotificationService.startService(context, this)
                    }

                    // Same idea for the widget refresh alarm: only worth scheduling if a
                    // widget is actually placed. WidgetReceiver.onDisabled() cancels it
                    // once the last widget is removed; this is the boot-time counterpart
                    // for "was already removed before this boot."
                    val widgetIds = AppWidgetManager.getInstance(context)
                        .getAppWidgetIds(ComponentName(context, WidgetReceiver::class.java))
                    if (widgetIds.isNotEmpty()) {
                        startAlarmManager(context)
                    }
                } catch (e: SecurityException) {
                    Timber.e(e, "Failed to start service or alarm")
                } catch (e: IllegalStateException) {
                    Timber.e(e, "Background execution limits prevented service start")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}