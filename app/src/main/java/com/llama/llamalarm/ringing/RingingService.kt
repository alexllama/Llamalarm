package com.llama.llamalarm.ringing

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import com.llama.llamalarm.LlamalarmApp
import com.llama.llamalarm.domain.RingPhase
import com.llama.llamalarm.domain.RingSource
import com.llama.llamalarm.domain.RingStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the ringing sound and drives the ringing state machine:
 * initial ring -> (retry after 5 min) / user snoozes -> missed-snooze re-rings.
 */
class RingingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var player: AlarmPlayer
    private lateinit var notifications: AlarmNotifications

    private val container get() = (application as LlamalarmApp).container

    override fun onCreate() {
        super.onCreate()
        player = AlarmPlayer(this, scope)
        notifications = AlarmNotifications(this)
        notifications.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getStringExtra(EXTRA_ALARM_ID)
        if (alarmId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Satisfy the foreground-service deadline immediately; replaced for ring actions below.
        startForeground(notifications.status(alarmId))

        when (intent.action) {
            ACTION_RING -> handleRing(
                alarmId,
                intent.getLongExtra(EXTRA_TRIGGER_AT, 0L),
            )

            ACTION_SILENCE -> handleSilence(alarmId)
            ACTION_STOP -> handleStop(alarmId)
            ACTION_SNOOZE -> handleSnooze(alarmId, intent.getLongExtra(EXTRA_SNOOZE_MS, 0L))
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    private fun handleRing(alarmId: String, triggerAt: Long) {
        Log.i(TAG, "ring alarm=$alarmId triggerAt=$triggerAt")
        scope.launch {
            val c = container
            val alarm = c.alarmRepository.get(alarmId)
            if (alarm == null) {
                shutdown()
                return@launch
            }
            val now = System.currentTimeMillis()
            val existing = c.instanceRepository.forAlarm(alarmId)
            val instance = when {
                existing == null || existing.phase == RingPhase.DONE ->
                    RingStateMachine.startScheduled(
                        alarmId,
                        if (triggerAt > 0) triggerAt else now,
                        now,
                    )

                existing.phase == RingPhase.WAITING_RETRY ->
                    RingStateMachine.onRingStarted(existing, RingSource.AUTO_RETRY, now)

                existing.phase == RingPhase.WAITING_SNOOZE ->
                    RingStateMachine.onRingStarted(
                        existing,
                        if (existing.source == RingSource.MISSED_RESNOOZE) {
                            RingSource.MISSED_RESNOOZE
                        } else {
                            RingSource.USER_SNOOZE
                        },
                        now,
                    )

                existing.phase == RingPhase.RINGING ->
                    if (existing.instanceTriggerAt == triggerAt) {
                        existing
                    } else {
                        RingStateMachine.startScheduled(alarmId, triggerAt, now)
                    }

                else -> RingStateMachine.startScheduled(alarmId, triggerAt, now)
            }
            c.instanceRepository.put(instance)
            notifications.cancelUpcoming(alarmId)

            val notification = notifications.ringing(alarm, instance)
            startForeground(notification)
            notifications.notifyRinging(notification)
            player.start(alarm, c.settingsRepository.current())
            c.scheduler.scheduleSilence(alarmId, now + RingStateMachine.AUTO_SILENCE_MS)
            launchRingingActivity(alarmId)
        }
    }

    private fun handleSilence(alarmId: String) {
        Log.i(TAG, "silence alarm=$alarmId")
        scope.launch {
            player.stop()
            val c = container
            val instance = c.instanceRepository.forAlarm(alarmId)
            if (instance == null || instance.phase != RingPhase.RINGING) {
                shutdown()
                return@launch
            }
            val next = RingStateMachine.onRingTimeout(instance, System.currentTimeMillis())
            if (next == null) {
                finishInstance(alarmId, instance.instanceTriggerAt)
            } else {
                c.instanceRepository.put(next)
                next.nextActionAt?.let { c.scheduler.scheduleInternalRing(alarmId, it) }
            }
            shutdown()
        }
    }

    private fun handleStop(alarmId: String) {
        Log.i(TAG, "stop alarm=$alarmId")
        scope.launch {
            player.stop()
            val c = container
            val instance = c.instanceRepository.forAlarm(alarmId)
            if (instance != null) finishInstance(alarmId, instance.instanceTriggerAt)
            shutdown()
        }
    }

    private fun handleSnooze(alarmId: String, durationMs: Long) {
        Log.i(TAG, "snooze alarm=$alarmId durationMs=$durationMs")
        scope.launch {
            player.stop()
            val c = container
            // The initial ring scheduled an auto-silence; it must not fire over the snoozed ring.
            c.scheduler.cancelSilence(alarmId)
            val instance = c.instanceRepository.forAlarm(alarmId)
            val next = instance?.let {
                RingStateMachine.snooze(it, System.currentTimeMillis(), durationMs)
            }
            if (next != null) {
                c.instanceRepository.put(next)
                next.nextActionAt?.let { c.scheduler.scheduleInternalRing(alarmId, it) }
            }
            shutdown()
        }
    }

    private suspend fun finishInstance(alarmId: String, triggerAt: Long) {
        val c = container
        c.instanceRepository.remove(alarmId)
        c.alarmRepository.onInstanceResolved(alarmId, triggerAt)
        c.scheduler.cancel(alarmId)
        c.scheduler.reschedule(alarmId)
    }

    private fun startForeground(notification: android.app.Notification) {
        ServiceCompat.startForeground(
            this,
            AlarmNotifications.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun launchRingingActivity(alarmId: String) {
        // The notification's full-screen intent opens the activity when the screen is off/locked.
        // A direct start is only possible in the background when the app may draw overlays.
        val canOverlay = Settings.canDrawOverlays(this)
        val fsiAllowed = getSystemService(NotificationManager::class.java)
            ?.canUseFullScreenIntent() ?: true
        Log.i(TAG, "launch ringing screen overlay=$canOverlay fullScreenIntent=$fsiAllowed")
        if (!canOverlay) return
        val intent = RingingActivity.intent(this, alarmId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(intent) }
    }

    private fun shutdown() {
        player.stop()
        notifications.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        player.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LlamalarmRing"
        const val ACTION_RING = "com.llama.llamalarm.service.RING"
        const val ACTION_SILENCE = "com.llama.llamalarm.service.SILENCE"
        const val ACTION_STOP = "com.llama.llamalarm.service.STOP"
        const val ACTION_SNOOZE = "com.llama.llamalarm.service.SNOOZE"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_TRIGGER_AT = "trigger_at"
        const val EXTRA_SNOOZE_MS = "snooze_ms"

        fun ringIntent(context: Context, alarmId: String, triggerAt: Long): Intent =
            Intent(context, RingingService::class.java)
                .setAction(ACTION_RING)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_TRIGGER_AT, triggerAt)

        fun silenceIntent(context: Context, alarmId: String): Intent =
            Intent(context, RingingService::class.java)
                .setAction(ACTION_SILENCE)
                .putExtra(EXTRA_ALARM_ID, alarmId)

        fun stopIntent(context: Context, alarmId: String): Intent =
            Intent(context, RingingService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_ALARM_ID, alarmId)

        fun snoozeIntent(context: Context, alarmId: String, durationMs: Long): Intent =
            Intent(context, RingingService::class.java)
                .setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_SNOOZE_MS, durationMs)
    }
}
