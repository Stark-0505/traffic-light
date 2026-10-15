package com.leekleak.trafficlight.util

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.Locale

/**
 * The user's 12/24-hour clock preference, resolved once and shared, instead of being asked
 * for again (`DateFormat.is24HourFormat(context)`) every time a label is formatted.
 *
 * That call reads a system setting over Binder; formatting 24 chart labels per expanded day
 * therefore meant 24 synchronous Binder round trips on the main thread, which is how upstream
 * ended up with a slow-Binder ANR report (upstream 7ac70ea). Here:
 *
 * - [is24Hour] is a [StateFlow]; formatting code reads the already-resolved Boolean.
 * - The setting is read on [ioDispatcher], never on the collector's (UI) thread.
 * - A [ContentObserver] on `Settings.System.TIME_12_24` keeps it current, so changing the
 *   system clock format while the app is open updates visible labels without a restart.
 * - Updates are resolved strictly one at a time, so two quick changes cannot publish out of
 *   order and leave a stale value.
 * - The observer exists only while something collects [is24Hour] (plus a 5s grace period for
 *   configuration changes) and is unregistered afterwards. Because collection restarts the
 *   upstream flow, the value is re-read on each re-subscription, which also catches a change
 *   made while nothing was observing.
 *
 * Until the first read completes, [is24Hour] holds the locale's default style, which needs no
 * Binder call.
 */
class TimeFormatSource internal constructor(
    context: Context,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    resolve: (Context) -> Boolean = { DateFormat.is24HourFormat(it) },
    initialValue: Boolean = localeDefaultIs24Hour(),
) {
    val is24Hour: StateFlow<Boolean> = observeIs24Hour(context.applicationContext, ioDispatcher, resolve)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialValue)

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Locale-derived guess; `getBestDateTimePattern` is pure ICU, no Binder involved. */
internal fun localeDefaultIs24Hour(locale: Locale = Locale.getDefault()): Boolean =
    DateFormat.getBestDateTimePattern(locale, "j").contains('H')

/**
 * Emits the resolved preference, then again after every change of `TIME_12_24`.
 * [resolve] is blocking (Binder) and is only ever invoked on [ioDispatcher].
 */
internal fun observeIs24Hour(
    appContext: Context,
    ioDispatcher: CoroutineDispatcher,
    resolve: (Context) -> Boolean,
): Flow<Boolean> = callbackFlow {
    // CONFLATED: any number of changes while a read is in flight collapse into one more read
    // *after* it, so the last write always wins.
    val changes = Channel<Unit>(Channel.CONFLATED)
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            changes.trySend(Unit)
        }
    }
    appContext.contentResolver.registerContentObserver(
        Settings.System.getUriFor(Settings.System.TIME_12_24), false, observer
    )

    launch {
        send(withContext(ioDispatcher) { resolve(appContext) })
        for (change in changes) {
            send(withContext(ioDispatcher) { resolve(appContext) })
        }
    }

    awaitClose {
        appContext.contentResolver.unregisterContentObserver(observer)
        changes.close()
    }
}.distinctUntilChanged()

/** The shared 12/24-hour preference as Compose state; lifecycle-aware. */
@Composable
fun rememberIs24HourFormat(): State<Boolean> =
    koinInject<TimeFormatSource>().is24Hour.collectAsStateWithLifecycle()
