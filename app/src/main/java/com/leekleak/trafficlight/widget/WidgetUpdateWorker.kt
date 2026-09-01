package com.leekleak.trafficlight.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

fun startAlarmManager(context: Context) {
    val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, WidgetUpdateReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    alarmManager.setInexactRepeating(
        AlarmManager.RTC,
        System.currentTimeMillis(),
        1000 * 60 * 1,
        pendingIntent
    )
}

fun killAlarmManager(context: Context) {
    val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, WidgetUpdateReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    alarmManager.cancel(pendingIntent)
}

/** Periodically refreshes the widget (today's usage figure) while the screen is on. */
class WidgetUpdateReceiver: BroadcastReceiver(), KoinComponent {
    private val applicationScope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                Widget().updateAll(context)
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "Failed to update all widgets: invalid ID or mismatch")
            } catch (e: IllegalStateException) {
                Timber.e(e, "Failed to update all widgets: invalid state")
            } catch (e: java.io.IOException) {
                Timber.e(e, "Failed to update all widgets: IO error")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
