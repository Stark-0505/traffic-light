package com.leekleak.trafficlight.services.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.DeadSystemException
import androidx.core.app.NotificationCompat
import com.leekleak.trafficlight.MainActivity
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.database.AppPreferenceRepo
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.database.DayUsage
import com.leekleak.trafficlight.database.TrafficSnapshot
import com.leekleak.trafficlight.database.UsageQuery
import com.leekleak.trafficlight.model.NetworkUsageManager
import com.leekleak.trafficlight.util.DataSize
import com.leekleak.trafficlight.util.clipAndPad
import com.leekleak.trafficlight.util.toKb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds

class SpeedNotification(
    serviceScope: CoroutineScope,
    context: Context,
    notificationManager: NotificationManager,
    notificationId: Int,
    private val networkUsageManager: NetworkUsageManager,
    private val connectivityManager: ConnectivityManager,
    private val appPreferenceRepo: AppPreferenceRepo,
    private val trafficSnapshot: TrafficSnapshot,
) : PersistentNotification(serviceScope, context, notificationManager, notificationId) {

    private var notificationBuilderSilent = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID_SILENT)
    private var updateCounter = Int.MAX_VALUE

    private val queryMobile = UsageQuery(dataType = DataType.Mobile)
    private val queryWifi = UsageQuery(dataType = DataType.Wifi)

    private var aodMode = false
    private var inBits = false
    private var separateUpDown = false
    private var liveNotification = false
    private var speedThreshold = false
    private var speedThresholdKb = -1L
    private var speedMetric = false
    private var sizeMetric = false
    private var todayUsage = DayUsage()

    init {
        scope.launch {
            appPreferenceRepo.modeAOD.collect { aodMode = it }
        }
        scope.launch {
            appPreferenceRepo.speedBits.collect { inBits = it; updateNotification(trafficSnapshot, true) }
        }
        scope.launch {
            appPreferenceRepo.separateUpDown.collect { separateUpDown = it; updateNotification(trafficSnapshot, true) }
        }
        scope.launch {
            appPreferenceRepo.liveNotification.collect { liveNotification = it; updateNotification(trafficSnapshot, true) }
        }
        scope.launch {
            appPreferenceRepo.speedThreshold.collect { speedThreshold = it; updateNotification(trafficSnapshot, true) }
        }
        scope.launch {
            appPreferenceRepo.speedThresholdKb.collect { speedThresholdKb = it; updateNotification(trafficSnapshot, true) }
        }
        scope.launch {
            appPreferenceRepo.speedMetric.collect { speedMetric = it; updateNotification(trafficSnapshot, true) }
        }
        scope.launch {
            appPreferenceRepo.sizeMetric.collect { sizeMetric = it; updateNotification(trafficSnapshot, true) }
        }
        updateBaseNotification()
    }

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            trafficSnapshot.updateSnapshot()
            trafficSnapshot.setCurrentAsLast()
            while (true) {
                trafficSnapshot.updateSnapshot()

                /**
                 * If network speed is changing rapidly, we use this while loop to self-calibrate
                 * the refresh timing to match the timing of the TrafficStats API updates.
                 *
                 * If network speed is not changing rapidly (i.e. it's zero)
                 * it's quite likely that the next tick will also be zero, so we ignore that and
                 * simply sleep for 1 second
                 */
                if (trafficSnapshot.isCurrentSameAsLast()) {
                    delay(100.milliseconds)
                    trafficSnapshot.updateSnapshot()
                }

                if (updateCounter >= DATA_UPDATE_FREQ) {
                    updateTodayUsage()
                    updateCounter = 0
                } else {
                    updateCounter++
                }

                updateNotification(trafficSnapshot)
                trafficSnapshot.setCurrentAsLast()
                delay(900.milliseconds)
            }
        }

    }

    override fun screenStateChange(on: Boolean) {
        if (on) start()
        else if (!aodMode) job?.cancel()
    }

    override fun cancel() {
        // TrafficSnapshot registers its own ConnectivityManager.NetworkCallback (see
        // TrafficSnapshot's class doc) - it's tied to this notification's lifetime, not
        // the wider service's, since a new TrafficSnapshot is created each time the user
        // re-enables the notification. Releasing it here (rather than only relying on the
        // scope-completion hook inside TrafficSnapshot itself, which only fires once the
        // whole service is torn down) means toggling the preference off/on repeatedly
        // within one service lifetime can't accumulate unregistered callbacks.
        trafficSnapshot.stopTrackingNetworkChanges()
        super.cancel()
    }

    private var lastTitle: String = ""
    private var lastContent: String = ""
    private suspend fun updateNotification(trafficSnapshot: TrafficSnapshot, force: Boolean = false) {
        val data = DataSize(trafficSnapshot.totalSpeed).toString(speed = true, inBits = inBits, metric = speedMetric)
        val upload = DataSize(trafficSnapshot.upSpeed).toString(speed = true, inBits = inBits, metric = speedMetric)
        val download = DataSize(trafficSnapshot.downSpeed).toString(speed = true, inBits = inBits, metric = speedMetric)
        val title = context.getString(R.string.up_down, upload, download)

        val spacing = 18
        val messageShort =
            context.getString(R.string.wi_fi, DataSize(todayUsage.usage2).toString(metric = sizeMetric)).clipAndPad(spacing) +
            context.getString(R.string.mobile, DataSize(todayUsage.usage1).toString(metric = sizeMetric))

        val silent = shouldGoSilent()
        if (!force) updateSilentTicks()
        // Cancel update only if there is no need to update the notification channel
        if (lastTitle == data && lastContent == messageShort && silent == shouldGoSilent() && !force) return

        lastTitle = data
        lastContent = messageShort

        val speed = data.substringBefore(" ")
        val unit = data.substringAfter(" ")
        notification = (
                if (shouldGoSilent()) {
                    if (liveNotification) {
                        buildForChannel(NOTIFICATION_CHANNEL_ID_SILENT)
                    } else {
                        notificationBuilderSilent
                    }
                } else {
                    if (liveNotification) {
                        buildForChannel(NOTIFICATION_CHANNEL_ID)
                    } else {
                        notificationBuilder
                    }
                }
            )
            .setRequestPromotedOngoing(liveNotification && !shouldGoSilent())
            .apply {
                if (!liveNotification) {
                    setSmallIcon(
                        if (!separateUpDown) {
                            notificationIconHelper.createIcon(speed, unit)
                        } else {
                            val speedUp = DataSize(trafficSnapshot.upSpeed).toStringParts(inBits = inBits, metric = speedMetric)
                            val speedDown = DataSize(trafficSnapshot.downSpeed).toStringParts(inBits = inBits, metric = speedMetric)
                            notificationIconHelper.createIconSeparate(
                                speed1 = "${speedUp.first} ${speedUp.third.substring(0,1)}",
                                speed2 = "${speedDown.first} ${speedDown.third.substring(0,1)}"
                            )
                        }
                    )
                }
                else  {
                    setSmallIcon(R.drawable.mobiledata_arrows)
                    setShortCriticalText(data)
                }
            }
            .setWhen(notificationOrderingWhen(System.currentTimeMillis())) // Keep above other notifications; see NotificationOrdering.kt
            .setShowWhen(false) // Hide timestamp
            .setContentTitle(title)
            .setContentText(messageShort)
            .build()
        notifySafely(notificationId, notification)
    }

    private suspend fun updateTodayUsage() {
        val date = LocalDate.now()
        val mobile = networkUsageManager.totalDayUsage(queryMobile, date)
        val wifi = networkUsageManager.totalDayUsage(queryWifi, date)
        todayUsage = DayUsage(date, mobile, wifi)
    }

    private fun buildForChannel(channel: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channel).apply {
            setSmallIcon(R.drawable.notification)
            setContentTitle(context.getString(R.string.app_name_short))
            setOngoing(true)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            setSilent(true)
            setLocalOnly(true)
            setOnlyAlertOnce(true)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            try {
                setContentIntent(
                    PendingIntent.getActivity(
                        context, 0, Intent(context, MainActivity::class.java).apply {
                            flags =
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }, PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } catch (_: DeadSystemException) {
                Timber.e("System died lol. Good luck!")
                cancel()
            }
        }
    }

    private fun updateBaseNotification() {
        notificationBuilder = buildForChannel(NOTIFICATION_CHANNEL_ID)
        notificationBuilderSilent = buildForChannel(NOTIFICATION_CHANNEL_ID_SILENT)

        notification = notificationBuilder.build()
    }

    private var silentChannelTicks: Long = 0
    private fun updateSilentTicks() {
        if ((speedThreshold &&
            (
                ((speedThresholdKb == -1L) && !isNetworkAvailable()) ||
                (trafficSnapshot.totalSpeed.toKb < speedThresholdKb)
            )
        )){
            silentChannelTicks++
        } else {
            silentChannelTicks = 0
        }
    }
    private fun shouldGoSilent(): Boolean = (silentChannelTicks >= SILENT_CHANNEL_TICK_TARGET) && speedThreshold

    private fun isNetworkAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)?.run {
                hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } ?: false
        } else {
            // minSdk is 28, so this branch is still live on real Android 9-11 devices,
            // not dead code - activeNetwork/getNetworkCapabilities() existed before API 31
            // too, but had known reliability gaps in multi-network scenarios on those
            // earlier versions, which is why this falls back to the (deprecated, but on
            // these OS versions more dependable) NetworkInfo API instead of lowering the
            // SDK_INT cutoff above.
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.run {
                when (type) {
                    ConnectivityManager.TYPE_WIFI -> true
                    ConnectivityManager.TYPE_MOBILE -> true
                    ConnectivityManager.TYPE_ETHERNET -> true
                    else -> false
                }
            } ?: false
        }
    }

    companion object {
        private const val DATA_UPDATE_FREQ = 4
        private const val SILENT_CHANNEL_TICK_TARGET = 4
        const val NOTIFICATION_CHANNEL_ID = "Persistent Notification"
        const val NOTIFICATION_CHANNEL_ID_SILENT = "Persistent Notification Silent"
    }
}