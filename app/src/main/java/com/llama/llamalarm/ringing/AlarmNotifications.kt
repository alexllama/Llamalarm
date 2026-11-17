package com.llama.llamalarm.ringing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.llama.llamalarm.R
import com.llama.llamalarm.domain.Alarm
import com.llama.llamalarm.domain.Format
import com.llama.llamalarm.domain.RingInstance
import com.llama.llamalarm.MainActivity
import com.llama.llamalarm.alarm.AlarmReceiver

class AlarmNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val ringing = NotificationChannel(
            CHANNEL_RINGING,
            "Alarm ringing",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Full-screen alarm while ringing"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val status = NotificationChannel(
            CHANNEL_STATUS,
            "Alarm status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Snoozed and upcoming alarms"
            setSound(null, null)
            enableVibration(false)
        }
        val upcoming = NotificationChannel(
            CHANNEL_UPCOMING,
            "Upcoming alarms",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Reminder before the next scheduled alarm"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannels(listOf(ringing, status, upcoming))
    }

    fun status(alarmId: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle("Llamalarm")
            .setContentText("Preparing alarm…")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(activityIntent(alarmId))
            .build()

    fun ringing(alarm: Alarm, instance: RingInstance): Notification {
        val title = if (alarm.label.isBlank()) "Alarm" else alarm.label
        val time = Format.timeOfDay(alarm.hour, alarm.minute)
        return NotificationCompat.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle(title)
            .setContentText(time)
            .setSubText("Snooze or stop")
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setSilent(true)
            .setContentIntent(activityIntent(alarm.id))
            .setFullScreenIntent(activityIntent(alarm.id), true)
            .addAction(
                R.drawable.ic_notification_alarm,
                "Stop",
                PendingIntent.getService(
                    context,
                    901,
                    RingingService.stopIntent(context, alarm.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    fun notifyRinging(notification: Notification) {
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    /** Dismissible "Alarm in 1h 58m" reminder posted ahead of the next occurrence. */
    fun upcoming(alarm: Alarm, triggerAt: Long): Notification {
        val remaining = (triggerAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val time = Format.timeOfDay(alarm.hour, alarm.minute)
        val text = if (alarm.label.isBlank()) time else "$time · ${alarm.label}"
        return NotificationCompat.Builder(context, CHANNEL_UPCOMING)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle("Alarm in ${Format.durationShort(remaining)}")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainActivityIntent())
            .addAction(0, "Dismiss", dismissUpcomingIntent(alarm.id))
            .build()
    }

    fun notifyUpcoming(alarmId: String, notification: Notification) {
        manager.notify(upcomingNotificationId(alarmId), notification)
    }

    fun cancelUpcoming(alarmId: String) {
        manager.cancel(upcomingNotificationId(alarmId))
    }

    private fun mainActivityIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            903,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun dismissUpcomingIntent(alarmId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            904,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_DISMISS_UPCOMING)
                .setData(android.net.Uri.parse("llamalarm://dismiss-upcoming/$alarmId"))
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun upcomingNotificationId(alarmId: String) =
        (alarmId.hashCode() and 0x0FFFFFFF) + 10_000

    private fun activityIntent(alarmId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            902,
            RingingActivity.intent(context, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_RINGING = "alarm_ringing"
        const val CHANNEL_STATUS = "alarm_status"
        const val CHANNEL_UPCOMING = "alarm_upcoming"
        const val NOTIFICATION_ID = 4201
    }
}
