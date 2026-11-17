package com.llama.llamalarm.ringing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.llama.llamalarm.alarm.AlarmScheduler
import com.llama.llamalarm.domain.Alarm
import com.llama.llamalarm.domain.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Plays the alarm sound/vibration for one ringing instance. Owned by [RingingService] so a single
 * player is active regardless of UI lifecycle.
 */
class AlarmPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var rampJob: Job? = null

    fun start(alarm: Alarm, settings: AppSettings) {
        stop()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val silentMode = audioManager?.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val volume = (alarm.volume ?: settings.volume).coerceIn(0.05f, 1f)
        val vibrate = alarm.vibrate ?: settings.vibrate
        if (settings.respectSilent && silentMode) {
            if (vibrate) startVibration()
            return
        }

        val uri = resolveRingtone(alarm)
        val usage = if (settings.respectHeadphones) {
            AudioAttributes.USAGE_MEDIA
        } else {
            AudioAttributes.USAGE_ALARM
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attributes)
            when {
                uri.scheme == "file" || uri.path?.startsWith("/") == true -> {
                    val path = uri.path ?: error("no path")
                    if (!File(path).exists()) {
                        mp.setDataSource(context, AlarmScheduler.defaultAlarmRingtoneUri())
                    } else {
                        mp.setDataSource(path)
                    }
                }

                else -> mp.setDataSource(context, uri)
            }
            mp.isLooping = true
            mp.setVolume(if (settings.gradualVolume) 0.05f else volume, volume)
            mp.setOnErrorListener { _, _, _ ->
                runCatching { fallbackPlay(mp, attributes, volume) }
                true
            }
            mp.prepare()
            mp.start()
            player = mp
        } catch (_: Exception) {
            runCatching { fallbackPlay(mp, attributes, volume) }
        }

        if (settings.gradualVolume) {
            rampJob = scope.launch {
                var v = 0.05f
                val target = volume
                while (v < target) {
                    delay(2_000)
                    v = (v + 0.15f).coerceAtMost(target)
                    runCatching { player?.setVolume(v, v) }
                }
            }
        }

        if (vibrate) startVibration()
    }

    private fun fallbackPlay(mp: MediaPlayer, attributes: AudioAttributes, volume: Float) {
        runCatching { mp.reset() }
        mp.setAudioAttributes(attributes)
        mp.setDataSource(context, AlarmScheduler.defaultAlarmRingtoneUri())
        mp.isLooping = true
        mp.setVolume(volume, volume)
        mp.prepare()
        mp.start()
        player = mp
    }

    private fun resolveRingtone(alarm: Alarm): Uri {
        val raw = alarm.ringtoneUri
        if (raw.isNullOrBlank()) return AlarmScheduler.defaultAlarmRingtoneUri()
        return if (raw.startsWith("content://") || raw.startsWith("file://")) {
            Uri.parse(raw)
        } else {
            Uri.fromFile(File(raw))
        }
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator = v
        val pattern = longArrayOf(0, 800, 600)
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    fun stop() {
        rampJob?.cancel()
        rampJob = null
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    companion object {
        fun defaultUri(): Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }
}
