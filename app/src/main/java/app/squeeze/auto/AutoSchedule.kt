package app.squeeze.auto

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * A recurring off-peak window in local wall-clock time. It belongs to the day it starts on; when
 * [end] is before [start] it runs past midnight into the next day. `start == end` is invalid and
 * never matches.
 */
data class TimeWindow(val days: Set<DayOfWeek>, val start: LocalTime, val end: LocalTime) {
    val isValid: Boolean get() = start != end && days.isNotEmpty()
    val wrapsMidnight: Boolean get() = end < start
}

enum class ScheduleMode { WINDOWS, WHENEVER_CHARGING, EXTERNAL_ONLY }

/**
 * Evaluates windows against local wall-clock time, so DST changes and travel never need stored
 * instants to be rewritten.
 */
object AutoSchedule {

    fun isActive(windows: List<TimeWindow>, now: ZonedDateTime): Boolean {
        val today = now.dayOfWeek
        val yesterday = today - 1
        val time = now.toLocalTime()
        return windows.filter { it.isValid }.any { w ->
            if (w.wrapsMidnight) {
                (today in w.days && time >= w.start) || (yesterday in w.days && time < w.end)
            } else {
                today in w.days && time >= w.start && time < w.end
            }
        }
    }

    /** Whether an auto run may start now, given the mode and whether an external trigger fired. */
    fun mayRun(mode: ScheduleMode, windows: List<TimeWindow>, now: ZonedDateTime, externallyTriggered: Boolean): Boolean =
        when (mode) {
            ScheduleMode.WHENEVER_CHARGING -> true
            ScheduleMode.EXTERNAL_ONLY -> externallyTriggered
            ScheduleMode.WINDOWS -> externallyTriggered || isActive(windows, now)
        }

    /**
     * When the currently active window ends, merging back-to-back windows (22:00–00:00 followed by
     * 00:00–06:00). Null when no window is active.
     */
    fun activeUntil(windows: List<TimeWindow>, now: ZonedDateTime): ZonedDateTime? {
        if (!isActive(windows, now)) return null
        var cursor = now
        repeat(MAX_CHAIN) {
            val end = currentWindowEnd(windows, cursor) ?: return cursor
            cursor = end
            if (!isActive(windows, cursor)) return cursor
        }
        return cursor
    }

    /** The next moment a window opens strictly after [now], or null if there are no valid windows. */
    fun nextStart(windows: List<TimeWindow>, now: ZonedDateTime): ZonedDateTime? {
        val valid = windows.filter { it.isValid }
        if (valid.isEmpty()) return null
        val startDate = now.toLocalDate()
        return (0..7).asSequence()
            .map { startDate.plusDays(it.toLong()) }
            .flatMap { date -> valid.filter { date.dayOfWeek in it.days }.map { at(date, it.start, now) } }
            .filter { it.isAfter(now) }
            .minOrNull()
    }

    private fun currentWindowEnd(windows: List<TimeWindow>, now: ZonedDateTime): ZonedDateTime? {
        val today = now.toLocalDate()
        val time = now.toLocalTime()
        return windows.filter { it.isValid }.mapNotNull { w ->
            when {
                w.wrapsMidnight && today.dayOfWeek in w.days && time >= w.start -> at(today.plusDays(1), w.end, now)
                w.wrapsMidnight && (today.dayOfWeek - 1) in w.days && time < w.end -> at(today, w.end, now)
                !w.wrapsMidnight && today.dayOfWeek in w.days && time >= w.start && time < w.end -> at(today, w.end, now)
                else -> null
            }
        }.maxOrNull()
    }

    /** Wall-clock to instant in [zoned]'s zone; times inside a DST gap move forward. */
    private fun at(date: LocalDate, time: LocalTime, zoned: ZonedDateTime): ZonedDateTime =
        ZonedDateTime.of(date, time, zoned.zone)

    private const val MAX_CHAIN = 14
}
