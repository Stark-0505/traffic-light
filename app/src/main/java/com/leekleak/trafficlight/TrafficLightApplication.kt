package com.leekleak.trafficlight

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_LOW
import com.leekleak.trafficlight.database.databaseModule
import com.leekleak.trafficlight.integrations.integrationsModule
import com.leekleak.trafficlight.model.managerModule
import com.leekleak.trafficlight.services.notifications.SpeedNotification
import com.leekleak.trafficlight.services.notifications.notificationModule
import com.leekleak.trafficlight.ui.navigation.navigationModule
import com.leekleak.trafficlight.ui.viewModelModule
import com.leekleak.trafficlight.widget.widgetAlarmController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext.startKoin
import timber.log.Timber

class TrafficLightApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        if (BuildConfig.DEBUG && Timber.forest().isEmpty()) {
            Timber.plant(Timber.DebugTree())
        }

        startKoin {
            androidContext(this@TrafficLightApplication)
            modules(
                systemServiceModule,
                databaseModule,
                managerModule,
                viewModelModule,
                navigationModule,
                notificationModule,
                integrationsModule
            )
        }
        syncWidgetAlarm()
    }

    /**
     * The refresh alarm is only worth having while a widget is placed. Checking costs two
     * Binder calls, so it runs off the main thread, and it must not take the process down.
     */
    private fun syncWidgetAlarm() {
        get<CoroutineScope>().launch(Dispatchers.IO) {
            try {
                widgetAlarmController(this@TrafficLightApplication).syncWithWidgets()
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync widget alarm")
            }
        }
    }

    private fun createNotificationChannel() {
        val speedChannel = NotificationChannel(
            SpeedNotification.NOTIFICATION_CHANNEL_ID,
            getString(R.string.persistent_notification),
            IMPORTANCE_DEFAULT
        ).apply {
            setShowBadge(false)
        }

        val speedChannelSilent = NotificationChannel(
            SpeedNotification.NOTIFICATION_CHANNEL_ID_SILENT,
            getString(R.string.persistent_notification_silent),
            IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannels(
            listOf(
                speedChannel,
                speedChannelSilent,
            )
        )
    }
}