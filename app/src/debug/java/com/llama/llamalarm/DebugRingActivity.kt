package com.llama.llamalarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.llama.llamalarm.ringing.RingingService

/**
 * Debug-build-only launcher used to exercise ring transitions from adb without waiting for
 * a real scheduled alarm. Not present in release builds.
 *
 * adb shell am start -n com.llama.llamalarm/.DebugRingActivity \
 *   --es cmd ring --es alarm_id <id> --el trigger_at <millis>
 */
class DebugRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val alarmId = intent.getStringExtra("alarm_id")
        if (alarmId == null) {
            finish()
            return
        }
        val command = intent.getStringExtra("cmd") ?: "ring"
        val serviceIntent = when (command) {
            "ring" -> RingingService.ringIntent(
                this,
                alarmId,
                intent.getLongExtra("trigger_at", 0L),
            )

            "snooze" -> RingingService.snoozeIntent(
                this,
                alarmId,
                intent.getLongExtra("snooze_ms", 5 * 60_000L),
            )

            "silence" -> RingingService.silenceIntent(this, alarmId)
            "stop" -> RingingService.stopIntent(this, alarmId)
            else -> null
        }
        if (serviceIntent != null) {
            ContextCompat.startForegroundService(this, serviceIntent)
        }
        finish()
    }
}
