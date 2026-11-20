package com.llama.llamalarm

import com.llama.llamalarm.domain.Alarm
import com.llama.llamalarm.domain.AlarmDefaults
import com.llama.llamalarm.domain.AlarmType
import com.llama.llamalarm.domain.Format
import com.llama.llamalarm.domain.OccurrenceCalculator
import com.llama.llamalarm.domain.RepeatDays
import com.llama.llamalarm.domain.RingPhase
import com.llama.llamalarm.domain.RingSource
import com.llama.llamalarm.domain.RingStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class OccurrenceTest {

    private val zone = ZoneOffset.UTC

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): Instant = LocalDateTime.of(year, month, day, hour, minute).toInstant(zone)

    private fun recurring(hour: Int = 9, day: DayOfWeek = DayOfWeek.SATURDAY) = Alarm(
        id = "a",
        hour = hour,
        minute = 0,
        type = AlarmType.RECURRING,
        repeatDaysMask = RepeatDays.maskOf(day),
    )

    @Test
    fun `daily alarm finds later today`() {
        val alarm = Alarm(
            id = "a",
            hour = 9,
            minute = 0,
            type = AlarmType.RECURRING,
            repeatDaysMask = RepeatDays.allMask(),
        )
        val next = OccurrenceCalculator.nextOccurrence(alarm, at(2026, 1, 1, 8), zone)
        assertEquals(at(2026, 1, 1, 9), next)
    }

    @Test
    fun `daily alarm rolls to tomorrow after the time passes`() {
        val alarm = Alarm(
            id = "a",
            hour = 9,
            minute = 0,
            type = AlarmType.RECURRING,
            repeatDaysMask = RepeatDays.allMask(),
        )
        val next = OccurrenceCalculator.nextOccurrence(alarm, at(2026, 1, 1, 9), zone)
        assertEquals(at(2026, 1, 2, 9), next)
    }

    @Test
    fun `pause window suppresses every occurrence inside it`() {
        // Saturdays at 09:00. Pause Jan 3 - Jan 17 inclusive should skip 3 Saturdays.
        val alarm = recurring().copy(
            pauseStartIso = "2026-01-03",
            pauseEndIso = "2026-01-17",
        )
        val next = OccurrenceCalculator.nextOccurrence(alarm, at(2026, 1, 1, 0), zone)
        assertEquals(at(2026, 1, 24, 9), next)
    }

    @Test
    fun `pause starting mid-week suppresses only matching occurrences`() {
        val alarm = recurring().copy(
            pauseStartIso = "2026-01-05",
            pauseEndIso = "2026-01-09",
        )
        // Next Saturday (Jan 3) is before the pause, so it still rings.
        assertEquals(
            at(2026, 1, 3, 9),
            OccurrenceCalculator.nextOccurrence(alarm, at(2026, 1, 1, 0), zone),
        )
    }

    @Test
    fun `skip next jumps one occurrence`() {
        val base = Alarm(
            id = "a",
            hour = 9,
            minute = 0,
            type = AlarmType.RECURRING,
            repeatDaysMask = RepeatDays.allMask(),
        )
        val now = at(2026, 1, 1, 8)
        val next = OccurrenceCalculator.nextOccurrenceIgnoringSkip(base, now, zone)!!
        val skipped = base.copy(skipNextAt = next.toEpochMilli())
        assertEquals(at(2026, 1, 2, 9), OccurrenceCalculator.nextOccurrence(skipped, now, zone))
    }

    @Test
    fun `one shot rings once in the future`() {
        val alarm = Alarm(
            id = "a",
            hour = 9,
            minute = 0,
            type = AlarmType.ONE_SHOT,
            dateIso = "2026-01-10",
        )
        assertEquals(
            at(2026, 1, 10, 9),
            OccurrenceCalculator.nextOccurrence(alarm, at(2026, 1, 1, 0), zone),
        )
        assertNull(OccurrenceCalculator.nextOccurrence(alarm, at(2026, 1, 11, 0), zone))
    }

    @Test
    fun `paused one shot does not ring`() {
        val alarm = Alarm(
            id = "a",
            hour = 9,
            minute = 0,
            type = AlarmType.ONE_SHOT,
            dateIso = "2026-01-10",
            pauseStartIso = "2026-01-05",
            pauseEndIso = "2026-01-15",
        )
        assertNull(OccurrenceCalculator.nextOccurrence(alarm, at(2026, 1, 1, 0), zone))
    }

    @Test
    fun `disabled alarms never ring`() {
        val alarm = recurring().copy(enabled = false)
        assertNull(OccurrenceCalculator.nextOccurrence(alarm, at(2026, 1, 1, 0), zone))
    }

    @Test
    fun `recurring start date delays the first ring`() {
        val alarm = recurring().copy(dateIso = LocalDate.of(2026, 2, 1).toString())
        assertEquals(
            at(2026, 2, 7, 9),
            OccurrenceCalculator.nextOccurrence(alarm, at(2026, 1, 1, 0), zone),
        )
    }
}

class RingStateMachineTest {

    private val t0 = 1_000_000L

    @Test
    fun `initial ring schedules 2 minute timeout`() {
        val instance = RingStateMachine.startScheduled("a", t0, t0)
        assertEquals(RingPhase.RINGING, instance.phase)
        assertEquals(RingSource.SCHEDULED, instance.source)
        assertEquals(t0 + RingStateMachine.AUTO_SILENCE_MS, instance.nextActionAt)
    }

    @Test
    fun `unattended initial ring retries once then gives up`() {
        val initial = RingStateMachine.startScheduled("a", t0, t0)
        val waiting = RingStateMachine.onRingTimeout(initial, t0 + 1)!!
        assertEquals(RingPhase.WAITING_RETRY, waiting.phase)
        assertEquals(RingSource.AUTO_RETRY, waiting.source)
        assertEquals(t0 + 1 + RingStateMachine.RETRY_DELAY_MS, waiting.nextActionAt)

        val retryRing = RingStateMachine.onRingStarted(waiting, RingSource.AUTO_RETRY, t0 + 2)
        assertEquals(RingPhase.RINGING, retryRing.phase)
        assertNull(RingStateMachine.onRingTimeout(retryRing, t0 + 3))
    }

    @Test
    fun `snoozing during initial ring cancels the retry`() {
        val initial = RingStateMachine.startScheduled("a", t0, t0)
        val snoozed = RingStateMachine.snooze(initial, t0 + 10, 15 * 60_000L)!!
        assertEquals(RingPhase.WAITING_SNOOZE, snoozed.phase)
        assertTrue(snoozed.initialRetrySuppressed)
        assertEquals(1, snoozed.userSnoozeCount)
        assertEquals(t0 + 10 + 15 * 60_000L, snoozed.nextActionAt)
        // A waiting snooze is not a ringing state, so a stray timeout must not trigger the retry path.
        val unchanged = RingStateMachine.onRingTimeout(snoozed, t0 + 20)!!
        assertEquals(RingPhase.WAITING_SNOOZE, unchanged.phase)
    }

    @Test
    fun `missed snoozed alarm re snoozes for the same duration, then gives up after 3 misses`() {
        val initial = RingStateMachine.startScheduled("a", t0, t0)
        val snoozed = RingStateMachine.snooze(initial, t0, 60_000L)!!

        // Miss 1
        val ring1 = RingStateMachine.onRingStarted(snoozed, RingSource.USER_SNOOZE, t0 + 60_000)
        val miss1 = RingStateMachine.onRingTimeout(ring1, t0 + 60_001)!!
        assertEquals(1, miss1.missCount)
        assertEquals(RingPhase.WAITING_SNOOZE, miss1.phase)
        assertEquals(t0 + 60_001 + 60_000L, miss1.nextActionAt)

        // Miss 2
        val ring2 = RingStateMachine.onRingStarted(miss1, RingSource.MISSED_RESNOOZE, t0 + 120_000)
        val miss2 = RingStateMachine.onRingTimeout(ring2, t0 + 120_001)!!
        assertEquals(2, miss2.missCount)

        // Miss 3 -> give up
        val ring3 = RingStateMachine.onRingStarted(miss2, RingSource.MISSED_RESNOOZE, t0 + 180_000)
        assertNull(RingStateMachine.onRingTimeout(ring3, t0 + 180_001))
    }

    @Test
    fun `user snoozes are capped at five`() {
        var instance = RingStateMachine.startScheduled("a", t0, t0)
        repeat(5) { i ->
            instance = RingStateMachine.snooze(instance, t0 + i * 1000L, 60_000L)!!
            instance = RingStateMachine.onRingStarted(
                instance,
                RingSource.USER_SNOOZE,
                t0 + i * 1000L + 60_000,
            )
        }
        assertEquals(5, instance.userSnoozeCount)
        assertFalse(RingStateMachine.canSnooze(instance))
        assertNull(RingStateMachine.snooze(instance, t0 + 999_999L, 60_000L))
    }
}

class AlarmDefaultsTest {

    private val zone = ZoneOffset.UTC

    @Test
    fun `new alarm starts at the current time rounded up today`() {
        val start = AlarmDefaults.startForNewAlarm(
            LocalDateTime.of(2026, 1, 1, 10, 42, 17).toInstant(zone),
            zone,
        )
        assertEquals(10, start.hour)
        assertEquals(45, start.minute)
        assertEquals(LocalDate.of(2026, 1, 1), start.date)
    }

    @Test
    fun `new alarm near midnight rolls to the next day`() {
        val start = AlarmDefaults.startForNewAlarm(
            LocalDateTime.of(2026, 1, 1, 23, 58).toInstant(zone),
            zone,
        )
        assertEquals(0, start.hour)
        assertEquals(0, start.minute)
        assertEquals(LocalDate.of(2026, 1, 2), start.date)
    }

    @Test
    fun `chosen time keeps today until it has passed`() {
        val now = LocalDateTime.of(2026, 1, 1, 10, 42).toInstant(zone)
        assertEquals(
            LocalDate.of(2026, 1, 1),
            AlarmDefaults.dateForTime(15, 30, now, zone),
        )
        assertEquals(
            LocalDate.of(2026, 1, 2),
            AlarmDefaults.dateForTime(9, 0, now, zone),
        )
    }
}

class FormatTest {

    @Test
    fun `time digits and AM PM are split cleanly`() {
        assertEquals("7:05", Format.timeDigits(7, 5))
        assertEquals("AM", Format.amPm(7))
        assertEquals("10:10", Format.timeDigits(10, 10))
        assertEquals("AM", Format.amPm(10))
        assertEquals("12:05", Format.timeDigits(12, 5))
        assertEquals("PM", Format.amPm(12))
    }
}
