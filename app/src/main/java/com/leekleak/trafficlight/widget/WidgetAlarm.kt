package com.leekleak.trafficlight.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/** How many [WidgetReceiver] widget instances are currently placed (0 if none). */
fun placedWidgetCount(context: Context): Int =
    AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, WidgetReceiver::class.java))
        .size

/**
 * Keeps the once-a-minute widget refresh alarm in line with whether any widget exists. An
 * alarm with no widget to refresh only wakes the process every minute to do nothing.
 *
 * The collaborators are injected so the policy is testable without Android; production use
 * goes through [widgetAlarmController].
 */
internal class WidgetAlarmController(
    private val placedWidgetCount: () -> Int,
    private val startAlarm: () -> Unit,
    private val cancelAlarm: () -> Unit,
) {
    /**
     * Brings the alarm in line with widget presence: schedules it if at least one widget is
     * placed (however many), cancels any existing one otherwise. For process start and for
     * widget enable/disable events.
     * @return whether any widget is placed.
     */
    fun syncWithWidgets(): Boolean {
        val hasWidgets = placedWidgetCount() > 0
        if (hasWidgets) startAlarm() else cancelAlarm()
        return hasWidgets
    }

    /**
     * For use *from the alarm's own tick*: cancels the alarm if no widget remains (e.g. the
     * last one was removed while the process was dead) and otherwise does nothing. It must
     * never (re)schedule: the repeating alarm is registered with a start time of "now", so
     * re-registering it from its own firing would make it fire again immediately, in a loop.
     * @return whether any widget is placed (i.e. whether the tick should do its update).
     */
    fun cancelIfNoWidgets(): Boolean {
        val hasWidgets = placedWidgetCount() > 0
        if (!hasWidgets) cancelAlarm()
        return hasWidgets
    }
}

internal fun widgetAlarmController(context: Context) = WidgetAlarmController(
    placedWidgetCount = { placedWidgetCount(context) },
    startAlarm = { startAlarmManager(context) },
    cancelAlarm = { killAlarmManager(context) },
)
