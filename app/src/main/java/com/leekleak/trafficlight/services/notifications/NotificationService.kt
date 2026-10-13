package com.leekleak.trafficlight.services.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.leekleak.trafficlight.database.AppPreferenceRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.core.parameter.parametersOf
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class NotificationService : LifecycleService() {
    private val appPreferenceRepo: AppPreferenceRepo by inject()
    private var foregroundNotification: PersistentNotification? = null
    private var notificationIDCounter = AtomicInteger(1)
    private val activeNotifications = Collections.synchronizedList(mutableListOf<PersistentNotification>())
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    activeNotifications.forEach { it.screenStateChange(true) }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    activeNotifications.forEach { it.screenStateChange(false) }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("Creating UsageService")

        // RECEIVER_NOT_EXPORTED: this receiver only cares about the system's own
        // ACTION_SCREEN_ON/OFF broadcasts, never broadcasts from other apps, so it has no
        // reason to be reachable by them. Apps targeting API 33+ must state this
        // explicitly for context-registered receivers; ContextCompat resolves to a no-op
        // flag on older platforms instead of requiring an SDK_INT check here.
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        lifecycleScope.launch {
            appPreferenceRepo.notification.collect { enabled ->
                if (enabled) {
                    val id = notificationIDCounter.getAndIncrement()
                    val notif = get<SpeedNotification> { parametersOf(lifecycleScope, id) }
                    activeNotifications.add(notif)
                    updateForegroundNotification()
                } else {
                    val notif = activeNotifications.find { it is SpeedNotification }
                    notif?.let { notification ->
                        activeNotifications.remove(notification)
                        updateForegroundNotification()
                        notification.cancel()
                    }
                    // Nothing left for this service to do - whether because the
                    // notification the user just turned off was the only one, or because
                    // it was already off when this collector received its first value
                    // (e.g. a boot-time start with the preference disabled). Either way,
                    // an empty, non-foreground LifecycleService has no reason to keep
                    // running: it would just sit there until the OS reclaims it, and
                    // START_STICKY would have the OS restart it right back into this same
                    // empty state.
                    if (activeNotifications.isEmpty()) {
                        Timber.i("No active notifications; stopping service")
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenStateReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Timber.i("Starting background service")
        updateForegroundNotification()
        return START_STICKY
    }

    fun updateForegroundNotification() {
        if (activeNotifications.contains(foregroundNotification)) return
        val firstNotification = activeNotifications.firstOrNull()

        if (firstNotification != null) {
            try {
                Timber.i("Starting foreground service")
                firstNotification.startForeground(this)
                firstNotification.start()
                foregroundNotification = firstNotification
            } catch (e: SecurityException) {
                Timber.e(e, "Failed to start foreground service due to permissions")
            } catch (e: IllegalStateException) {
                Timber.e(e, "Failed to start foreground service due to state")
            }
        } else if (foregroundNotification != null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundNotification = null
        }
    }

    companion object {
        /**
         * Starts the service immediately on the calling thread. Prefer this from broadcast
         * receivers: launching it asynchronously (the overload below) can run it after the
         * receiver has already finished, when the system's short post-broadcast window for
         * background service starts may be over.
         */
        fun startService(context: Context) {
            try {
                context.startService(Intent(context, NotificationService::class.java))
            } catch (e: SecurityException) {
                Timber.e(e, "Failed to start NotificationService due to permissions")
            } catch (e: IllegalStateException) {
                Timber.e(e, "Failed to start NotificationService due to background limits")
            }
        }

        fun startService(context: Context, scope: CoroutineScope) {
            scope.launch { startService(context) }
        }
    }
}
