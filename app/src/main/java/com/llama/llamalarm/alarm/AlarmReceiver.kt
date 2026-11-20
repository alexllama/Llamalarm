package com.llama.llamalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.llama.llamalarm.ringing.AlarmNotifications
import com.llama.llamalarm.ringing.RingingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS_UPCOMING) {
            // Dismissing the "Alarm in X" reminder means the user does not want that alarm to
            // ring. Merely hiding the notification left the scheduled ring intact, so skip the
            // pending occurrence and re-arm the alarm for the one after it.
            val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
            val pending = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    AlarmNotifications(context).cancelUpcoming(alarmId)
                    val app = context.applicationContext as com.llama.llamalarm.LlamalarmApp
                    app.container.alarmRepository.skipNext(alarmId)
                    app.container.scheduler.reschedule(alarmId)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
        if (intent.action == ACTION_UPCOMING) {
            val app = context.applicationContext as com.llama.llamalarm.LlamalarmApp
            val alarm = app.container.alarmRepository.get(alarmId) ?: return
            val triggerAt = intent.getLongExtra(EXTRA_TRIGGER_AT, 0L)
            if (triggerAt <= System.currentTimeMillis()) return
            val notifications = AlarmNotifications(context).apply { ensureChannels() }
            notifications.notifyUpcoming(alarmId, notifications.upcoming(alarm, triggerAt))
            return
        }
        val serviceIntent = when (intent.action) {
            ACTION_RING -> RingingService.ringIntent(
                context,
                alarmId,
                intent.getLongExtra(EXTRA_TRIGGER_AT, 0L),
            )

            ACTION_SILENCE -> RingingService.silenceIntent(context, alarmId)
            else -> return
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    companion object {
        const val ACTION_RING = "com.llama.llamalarm.action.RING"
        const val ACTION_SILENCE = "com.llama.llamalarm.action.SILENCE"
        const val ACTION_UPCOMING = "com.llama.llamalarm.action.UPCOMING"
        const val ACTION_DISMISS_UPCOMING = "com.llama.llamalarm.action.DISMISS_UPCOMING"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_TRIGGER_AT = "trigger_at"
    }
}

/** Re-arms alarms after reboot, app updates, time changes, and timezone changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                val pending = goAsync()
                val app = context.applicationContext as com.llama.llamalarm.LlamalarmApp
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        app.container.scheduler.rescheduleAll()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
