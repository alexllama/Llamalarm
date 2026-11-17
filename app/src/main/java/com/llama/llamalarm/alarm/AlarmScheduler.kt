package com.llama.llamalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import com.llama.llamalarm.MainActivity
import com.llama.llamalarm.R
import com.llama.llamalarm.domain.Alarm
import com.llama.llamalarm.domain.AlarmType
import com.llama.llamalarm.domain.RingInstance
import com.llama.llamalarm.domain.RingPhase
import com.llama.llamalarm.ringing.AlarmNotifications
import java.time.Instant
import java.time.ZoneId

/**
 * Owns all AlarmManager interactions:
 *  - the next user-facing alarm occurrence (setAlarmClock)
 *  - internal ring/silence actions for the ringing state machine (exact alarms)
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    private val notifications = AlarmNotifications(context)

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    private fun container() = (context.applicationContext as com.llama.llamalarm.LlamalarmApp).container

    /**
     * @param cancelNotice also remove any upcoming-alarm notification that is already on screen.
     * Off during startup rescheduling so a notice the user is looking at isn't wiped.
     */
    fun rescheduleAll(cancelNotice: Boolean = false) = kotlinx.coroutines.runBlocking {
        val container = container()
        val alarms = container.alarmRepository.alarms.value
        alarms.forEach { alarm -> runCatching { reschedule(alarm.id, cancelNotice) } }
        // Restore waiting instances that were interrupted by a reboot/process death.
        container.instanceRepository.instances.value
            .filter { it.phase == RingPhase.WAITING_RETRY || it.phase == RingPhase.WAITING_SNOOZE }
            .forEach { instance ->
                instance.nextActionAt?.let { at ->
                    if (at <= System.currentTimeMillis()) {
                        startRingNow(instance)
                    } else {
                        scheduleInternalRing(instance.alarmId, at)
                    }
                }
            }
    }

    suspend fun reschedule(alarmId: String, cancelNotice: Boolean = true) {
        val container = container()
        val repository = container.alarmRepository
        val alarm = repository.get(alarmId) ?: return
        cancel(alarmId)
        if (cancelNotice) notifications.cancelUpcoming(alarmId)
        val next = if (alarm.enabled) {
            com.llama.llamalarm.domain.OccurrenceCalculator.nextOccurrence(alarm, Instant.now())
        } else {
            null
        }
        if (next == null) {
            notifications.cancelUpcoming(alarmId)
            if (alarm.type == AlarmType.ONE_SHOT && alarm.enabled) {
                repository.setEnabled(alarmId, false)
            }
            repository.updateNextTrigger(alarmId, null)
            return
        }
        repository.updateNextTrigger(alarmId, next.toEpochMilli())
        scheduleUserAlarm(alarmId, next.toEpochMilli())
        scheduleUpcomingNotice(alarm, next.toEpochMilli())
    }

    /** Remove an already-posted "Alarm in X" notification for this alarm. */
    fun cancelShownNotice(alarmId: String) = notifications.cancelUpcoming(alarmId)

    private fun scheduleUpcomingNotice(alarm: Alarm, triggerAt: Long) {
        if (!container().settingsRepository.current().upcomingNotification) return
        if (!canScheduleExactAlarms()) return
        val postAt = triggerAt - UPCOMING_LEAD_MS
        if (postAt <= System.currentTimeMillis() + 30_000L) return
        AlarmManagerCompat.setExactAndAllowWhileIdle(
            alarmManager,
            AlarmManager.RTC_WAKEUP,
            postAt,
            upcomingPendingIntent(alarm.id, triggerAt),
        )
    }

    fun scheduleUserAlarm(alarmId: String, triggerAt: Long) {
        if (!canScheduleExactAlarms()) {
            // Without exact-alarm permission we cannot guarantee delivery; the UI prompts for it.
            return
        }
        val showIntent = PendingIntent.getActivity(
            context,
            showRequestCode(alarmId),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, showIntent),
            ringPendingIntent(alarmId),
        )
    }

    fun scheduleInternalRing(alarmId: String, triggerAt: Long) {
        if (!canScheduleExactAlarms()) return
        AlarmManagerCompat.setExactAndAllowWhileIdle(
            alarmManager,
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            ringPendingIntent(alarmId),
        )
    }

    fun scheduleSilence(alarmId: String, triggerAt: Long) {
        if (!canScheduleExactAlarms()) return
        AlarmManagerCompat.setExactAndAllowWhileIdle(
            alarmManager,
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            silencePendingIntent(alarmId),
        )
    }

    fun scheduleWaiting(alarmId: String, instance: RingInstance) {
        instance.nextActionAt?.let { scheduleInternalRing(alarmId, it) }
    }

    fun cancel(alarmId: String) {
        alarmManager.cancel(ringPendingIntent(alarmId))
        alarmManager.cancel(silencePendingIntent(alarmId))
        alarmManager.cancel(upcomingPendingIntent(alarmId, 0L))
    }

    fun cancelSilence(alarmId: String) {
        alarmManager.cancel(silencePendingIntent(alarmId))
    }

    private fun startRingNow(instance: RingInstance) {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_RING)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, instance.alarmId)
            .putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, instance.instanceTriggerAt)
        context.sendBroadcast(intent)
    }

    private fun ringPendingIntent(alarmId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_RING)
            .setData(Uri.parse("llamalarm://ring/$alarmId"))
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(
            context,
            ringRequestCode(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun silencePendingIntent(alarmId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_SILENCE)
            .setData(Uri.parse("llamalarm://silence/$alarmId"))
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(
            context,
            silenceRequestCode(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun upcomingPendingIntent(alarmId: String, triggerAt: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_UPCOMING)
            .setData(Uri.parse("llamalarm://upcoming/$alarmId"))
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
            .putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, triggerAt)
        return PendingIntent.getBroadcast(
            context,
            upcomingRequestCode(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val UPCOMING_LEAD_MS = 2 * 60 * 60 * 1000L

        private fun ringRequestCode(alarmId: String) = alarmId.hashCode() and 0x0FFFFFFF
        private fun silenceRequestCode(alarmId: String) = (alarmId.hashCode() or 0x40000000)
        private fun showRequestCode(alarmId: String) = (alarmId.hashCode() or 0x20000000)
        private fun upcomingRequestCode(alarmId: String) = (alarmId.hashCode() or 0x30000000)

        fun defaultAlarmRingtoneUri(): Uri =
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
    }
}
