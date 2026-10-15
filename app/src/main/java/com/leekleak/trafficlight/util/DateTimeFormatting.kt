package com.leekleak.trafficlight.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

fun LocalDateTime.toTimestamp(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun LocalDate.toTimestamp(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun fromTimestamp(stamp: Long): LocalDateTime {
    return LocalDateTime.ofInstant(
        Instant.ofEpochMilli(stamp),
        ZoneId.systemDefault()
    )
}

/**
 * Formats a time of day as `HH:mm` (24-hour) or `hh:mm a` / `hh a` (12-hour, [short]).
 *
 * Pure: the caller supplies the already-resolved clock preference (see
 * [rememberIs24HourFormat]) rather than this function asking the system on every call -
 * resolving it is a Binder round trip, and charts format one label per hour.
 */
fun LocalTime.toLocaleHourString(
    is24HourFormat: Boolean,
    short: Boolean = false,
    locale: Locale = Locale.getDefault(),
): String {
    val pattern = if (is24HourFormat) "HH:mm"
        else (if (short) "hh a" else "hh:mm a")
    return format(DateTimeFormatter.ofPattern(pattern, locale))
}

fun DayOfWeek.getName(style: TextStyle) =
    this.getDisplayName(style, Locale.getDefault()).replaceFirstChar(Char::titlecase)

fun Month.getName(style: TextStyle) =
    this.getDisplayName(style, Locale.getDefault()).replaceFirstChar(Char::titlecase)

/**
 * Calculates what percentage of ratio 1 is covered by ratio 2
 */
fun overlapRatio(range1: ClosedRange<Long>, range2: ClosedRange<Long>): Double {
    val overlapStart = maxOf(range1.start, range2.start)
    val overlapEnd = minOf(range1.endInclusive, range2.endInclusive)

    val overlapLength = maxOf(0L, overlapEnd - overlapStart).toDouble()
    val range1Length = range1.endInclusive - range1.start

    if (range1Length == 0L) return if (range2.contains(range1.start)) 1.0 else 0.0

    return overlapLength / (range1Length.toDouble())
}
