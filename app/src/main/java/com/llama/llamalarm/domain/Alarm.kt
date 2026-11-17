package com.llama.llamalarm.domain

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@Serializable
enum class AlarmType { ONE_SHOT, RECURRING }

@Serializable
data class Alarm(
    val id: String,
    val label: String = "",
    val enabled: Boolean = true,
    val type: AlarmType = AlarmType.RECURRING,
    val hour: Int,
    val minute: Int,
    /** For ONE_SHOT: the date it fires on. For RECURRING: optional first date (inclusive). */
    val dateIso: String? = null,
    /** Bitmask of DayOfWeek.value - 1 (Monday = bit 0 ... Sunday = bit 6). Unused for ONE_SHOT. */
    val repeatDaysMask: Int = 0,
    /** Copied-to-app-storage absolute path, or a content URI string. */
    val ringtoneUri: String? = null,
    val ringtoneName: String? = null,
    /** Per-alarm overrides. Null means use the global setting. */
    val volume: Float? = null,
    val vibrate: Boolean? = null,
    /** Epoch millis of a single occurrence that should be skipped. */
    val skipNextAt: Long? = null,
    /** Inclusive pause window as ISO dates. Any occurrence in this range is suppressed. */
    val pauseStartIso: String? = null,
    val pauseEndIso: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Cached next trigger for display/debugging. Not authoritative. */
    val nextTriggerAt: Long? = null,
) {
    val time: LocalTime get() = LocalTime.of(hour, minute)

    val date: LocalDate? get() = dateIso?.let(LocalDate::parse)
    val pauseStart: LocalDate? get() = pauseStartIso?.let(LocalDate::parse)
    val pauseEnd: LocalDate? get() = pauseEndIso?.let(LocalDate::parse)

    fun hasRepeatDay(day: DayOfWeek): Boolean =
        repeatDaysMask and (1 shl (day.value - 1)) != 0

    fun repeatDays(): List<DayOfWeek> =
        DayOfWeek.entries.filter(::hasRepeatDay)

    fun isPausedOn(date: LocalDate): Boolean {
        val start = pauseStart ?: return false
        val end = pauseEnd ?: start
        return !date.isBefore(start) && !date.isAfter(end)
    }

    fun scheduleSummary(): String = when (type) {
        AlarmType.ONE_SHOT -> {
            val d = date
            if (d == null) "Once" else "Once on " + d.format(Format.dateMedium)
        }

        AlarmType.RECURRING -> {
            val days = repeatDays()
            when {
                days.isEmpty() -> "Never"
                days.size == 7 -> "Every day"
                days == listOf(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                ) -> "Weekdays"
                days == listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
                else -> days.joinToString(" ") { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
            }
        }
    }
}

object RepeatDays {
    fun maskOf(vararg days: DayOfWeek): Int = days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }
    fun toggle(mask: Int, day: DayOfWeek): Int = mask xor (1 shl (day.value - 1))
    fun contains(mask: Int, day: DayOfWeek): Boolean = mask and (1 shl (day.value - 1)) != 0
    fun allMask(): Int = 0b111_1111
}

@Serializable
data class SnoozePreset(
    val durationMs: Long,
)

@Serializable
data class AppSettings(
    /** Duration in millis, ordered as displayed. Defaults: 5m, 15m, 1h, 4h, 8h, 24h. */
    val snoozePresets: List<Long> = DEFAULT_SNOOZE_PRESETS,
    val vibrate: Boolean = false,
    /** Ring volume 0..1. Applied to the alarm stream playback. */
    val volume: Float = 1f,
    /** When false (default), the alarm rings even in silent/vibrate mode. */
    val respectSilent: Boolean = false,
    /** When false (default), the alarm rings through headphones too. */
    val respectHeadphones: Boolean = false,
    val gradualVolume: Boolean = false,
    /** Post a dismissible "Alarm in X" notification ahead of the next occurrence. */
    val upcomingNotification: Boolean = true,
    /** The last ringtone the user chose; used as the starting point for new alarms. */
    val defaultRingtoneUri: String? = null,
    val defaultRingtoneName: String? = null,
    /** Bumped when a stored-settings migration needs to run once. */
    val settingsVersion: Int = 0,
    val onboardingDone: Boolean = false,
) {
    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        val DEFAULT_SNOOZE_PRESETS = listOf(5 * MINUTE, 15 * MINUTE, HOUR, 4 * HOUR, 8 * HOUR, DAY)
        const val MAX_PRESETS = 6
    }
}

@Serializable
enum class RingSource { SCHEDULED, AUTO_RETRY, USER_SNOOZE, MISSED_RESNOOZE }

@Serializable
enum class RingPhase { RINGING, WAITING_RETRY, WAITING_SNOOZE, DONE }

/** Durable runtime state for one fired alarm instance, survives process death. */
@Serializable
data class RingInstance(
    val alarmId: String,
    /** The original scheduled trigger instant, identifies this fired occurrence. */
    val instanceTriggerAt: Long,
    val source: RingSource,
    val phase: RingPhase,
    val userSnoozeCount: Int = 0,
    val missCount: Int = 0,
    val lastSnoozeMs: Long? = null,
    /** True once the user snoozes during the initial ring; disables the 2min/5min retry path. */
    val initialRetrySuppressed: Boolean = false,
    /** When the next ring/silence action is due. */
    val nextActionAt: Long? = null,
    val updatedAt: Long = 0,
) {
    val isRinging: Boolean get() = phase == RingPhase.RINGING
}

object Format {
    val dateMedium: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")
    val dateFull: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
    val time12: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("h:mm")

    /** "5 min", "1 hr 30 min", "24 hr" */
    fun duration(ms: Long): String {
        val d = Duration.ofMillis(ms)
        val hours = d.toHours()
        val minutes = d.toMinutesPart()
        return when {
            hours == 0L && minutes == 0 -> "${d.toMinutes()} min"
            hours == 0L -> "$minutes min"
            minutes == 0 -> "$hours hr"
            else -> "$hours hr $minutes min"
        }
    }

    /** Short form for buttons: "5m", "1h", "1h 30m", "24h". */
    fun durationShort(ms: Long): String {
        val d = Duration.ofMillis(ms)
        val hours = d.toHours()
        val minutes = d.toMinutesPart()
        return when {
            hours == 0L -> "${d.toMinutes()}m"
            minutes == 0 -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    fun timeOfDay(hour: Int, minute: Int, is24h: Boolean = false): String =
        LocalTime.of(hour, minute).let {
            if (is24h) it.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            else it.format(time12) + " " + if (it.hour < 12) "AM" else "PM"
        }

    fun relativeToNow(instant: Instant, now: Instant = Instant.now()): String {
        val ms = instant.toEpochMilli() - now.toEpochMilli()
        if (ms <= 0) return "now"
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "in ${minutes.coerceAtLeast(1)} min"
            minutes < 60 * 24 -> "in ${Duration.ofMinutes(minutes).toHours()} hr"
            else -> "in ${Duration.ofMinutes(minutes).toDays()} days"
        }
    }
}

object OccurrenceCalculator {
    private const val MAX_DAYS_AHEAD = 400

    /**
     * Returns the next trigger instant strictly after [after], honoring one-shot dates,
     * repeat days, the optional recurring start date, the pause window, and skip-next.
     */
    fun nextOccurrence(
        alarm: Alarm,
        after: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? {
        if (!alarm.enabled) return null
        val afterLocal = after.atZone(zone).toLocalDateTime()

        return when (alarm.type) {
            AlarmType.ONE_SHOT -> {
                val date = alarm.date ?: return null
                val candidate = LocalDateTime.of(date, alarm.time).atZone(zone)
                when {
                    !candidate.toInstant().isAfter(after) -> null
                    alarm.isPausedOn(date) -> null
                    alarm.skipNextAt == candidate.toInstant().toEpochMilli() -> null
                    else -> candidate.toInstant()
                }
            }

            AlarmType.RECURRING -> {
                if (alarm.repeatDaysMask == 0) return null
                val start = alarm.date
                val startInstant = start?.let {
                    LocalDateTime.of(it, alarm.time).atZone(zone).toInstant()
                }
                val firstDay = afterLocal.toLocalDate()
                for (i in 0..MAX_DAYS_AHEAD) {
                    val day = firstDay.plusDays(i.toLong())
                    if (!alarm.hasRepeatDay(day.dayOfWeek)) continue
                    val candidate = LocalDateTime.of(day, alarm.time).atZone(zone)
                    val candidateInstant = candidate.toInstant()
                    if (startInstant != null && candidateInstant.isBefore(startInstant)) continue
                    if (!candidateInstant.isAfter(after)) continue
                    if (alarm.isPausedOn(day)) continue
                    if (alarm.skipNextAt == candidateInstant.toEpochMilli()) continue
                    return candidateInstant
                }
                null
            }
        }
    }

    /** Next occurrence ignoring skip-next (used to decide which instance "skip next" targets). */
    fun nextOccurrenceIgnoringSkip(
        alarm: Alarm,
        after: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? = nextOccurrence(alarm.copy(skipNextAt = null), after, zone)

    /** Next occurrence after this one (used to preview what skip-next will jump to). */
    fun occurrenceAfter(
        alarm: Alarm,
        current: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? = nextOccurrence(alarm.copy(skipNextAt = null), current, zone)
}
