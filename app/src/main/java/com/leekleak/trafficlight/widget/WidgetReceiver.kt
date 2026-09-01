package com.leekleak.trafficlight.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SCREEN_OFF
import android.content.Intent.ACTION_SCREEN_ON
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class WidgetReceiver: GlanceAppWidgetReceiver(), KoinComponent {
    private val applicationScope: CoroutineScope by inject()
    override val glanceAppWidget: GlanceAppWidget = Widget()

    @SuppressLint("MissingSuperCall")
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        registerReceiver(context)

        applicationScope.launch {
            for (appWidgetId in appWidgetIds) {
                try {
                    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
                    glanceAppWidget.update(context, glanceId)
                } catch (e: IllegalArgumentException) {
                    Timber.e(e, "Failed to update widget: invalid ID or mismatch")
                } catch (e: IllegalStateException) {
                    Timber.e(e, "Failed to update widget: invalid state")
                } catch (e: java.io.IOException) {
                    Timber.e(e, "Failed to update widget: IO error")
                }
            }
            pendingResult.finish()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SCREEN_ON -> {
                startAlarmManager(context)
            }
            ACTION_SCREEN_OFF -> {
                killAlarmManager(context)
            }
            else -> {
                super.onReceive(context, intent)
            }
        }
    }

    override fun onDisabled(context: Context?) {
        // onDisabled() fires when the last widget instance is removed. Without this, a
        // currently-scheduled alarm (screen was on when the widget got removed) would
        // keep firing once a minute forever - there's no more SCREEN_ON/OFF receiver
        // listening to stop it once unregisterReceiver() below runs.
        context?.let {
            killAlarmManager(it)
            unregisterReceiver(it)
        }
        super.onDisabled(context)
    }

    fun registerReceiver(context: Context) {
        if (registered.exchange(true)) return
        ContextCompat.registerReceiver(
            context.applicationContext,
            this,
            IntentFilter().apply {
                addAction(ACTION_SCREEN_ON)
                addAction(ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterReceiver(context: Context) {
        if (!registered.load()) return
        try {
            context.applicationContext.unregisterReceiver(this)
            registered.store(false)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "WidgetReceiver not registered")
        }
    }

    companion object {
        private var registered = AtomicBoolean(false)
    }
}