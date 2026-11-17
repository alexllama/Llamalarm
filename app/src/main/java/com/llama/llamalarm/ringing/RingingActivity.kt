package com.llama.llamalarm.ringing

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.llama.llamalarm.LlamalarmApp
import com.llama.llamalarm.domain.RingPhase
import com.llama.llamalarm.ui.theme.LlamalarmTheme

/**
 * Full-screen alarm surface: wakes the display, shows over the lock screen, and lets the user
 * snooze with any preset or stop with a single tap.
 */
class RingingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID)
        if (alarmId == null) {
            finish()
            return
        }

        val container = (application as LlamalarmApp).container
        setContent {
            LlamalarmTheme {
                val alarms by container.alarmRepository.alarms.collectAsState()
                val instances by container.instanceRepository.instances.collectAsState()
                val settings by container.settingsRepository.settings.collectAsState()
                val alarm = alarms.firstOrNull { it.id == alarmId }
                val instance = instances.firstOrNull { it.alarmId == alarmId }
                var wasRinging by remember { mutableStateOf(false) }

                LaunchedEffect(instance?.phase) {
                    val ringing = instance?.phase == RingPhase.RINGING
                    if (ringing) wasRinging = true
                    if (wasRinging && !ringing && alarm != null) finish()
                }

                if (alarm == null) {
                    LaunchedEffect(Unit) { finish() }
                } else {
                    RingingScreen(
                        alarm = alarm,
                        instance = instance,
                        presets = settings.snoozePresets,
                        onStop = {
                            startService(RingingService.stopIntent(this@RingingActivity, alarmId))
                            finish()
                        },
                        onSnooze = { durationMs ->
                            startService(
                                RingingService.snoozeIntent(this@RingingActivity, alarmId, durationMs),
                            )
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"

        fun intent(context: Context, alarmId: String): Intent =
            Intent(context, RingingActivity::class.java)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
