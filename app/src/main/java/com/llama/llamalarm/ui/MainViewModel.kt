package com.llama.llamalarm.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llama.llamalarm.LlamalarmApp
import com.llama.llamalarm.domain.Alarm
import com.llama.llamalarm.domain.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as LlamalarmApp).container
    private val repository = container.alarmRepository
    private val settingsRepository = container.settingsRepository
    private val scheduler = container.scheduler

    val alarms: StateFlow<List<Alarm>> = repository.alarms
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    fun getAlarm(id: String): Alarm? = repository.get(id)

    fun canScheduleExactAlarms(): Boolean = scheduler.canScheduleExactAlarms()

    fun saveAlarm(alarm: Alarm, isNew: Boolean) {
        viewModelScope.launch {
            val saved = if (isNew) repository.create(alarm) else repository.upsert(alarm.copy(id = alarm.id))
            scheduler.reschedule(saved.id)
        }
    }

    fun deleteAlarm(id: String) {
        viewModelScope.launch {
            scheduler.cancel(id)
            scheduler.cancelShownNotice(id)
            repository.delete(id)
            container.instanceRepository.remove(id)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(id, enabled)
            scheduler.reschedule(id)
        }
    }

    fun skipNext(id: String) {
        viewModelScope.launch {
            repository.skipNext(id)
            scheduler.reschedule(id)
        }
    }

    fun pause(id: String, start: LocalDate, end: LocalDate) {
        viewModelScope.launch {
            repository.pause(id, start, end)
            scheduler.reschedule(id)
        }
    }

    fun clearPause(id: String) {
        viewModelScope.launch {
            repository.clearPause(id)
            scheduler.reschedule(id)
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val before = settingsRepository.current()
            settingsRepository.update(transform)
            if (before.upcomingNotification != settingsRepository.current().upcomingNotification) {
                scheduler.rescheduleAll(cancelNotice = true)
            }
        }
    }

    fun importRingtone(uri: Uri, onDone: (path: String, name: String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.displayName(uri) ?: "ringtone"
                    val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "audio"
                    val dir = File(getApplication<Application>().filesDir, "ringtones").apply { mkdirs() }
                    val target = File(dir, "${UUID.randomUUID()}.$extension")
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Unable to read ringtone")

                    val actualName = File(name).nameWithoutExtension.ifBlank { "Custom ringtone" }
                    target.absolutePath to actualName
                }
            }
            result.getOrNull()?.let { (path, name) -> onDone(path, name) }
        }
    }

    fun clearImportedRingtone(path: String?) {
        if (path == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (file.canonicalPath.startsWith(File(getApplication<Application>().filesDir, "ringtones").canonicalPath)) {
                    file.delete()
                }
            }
        }
    }

    private fun ContentResolver.displayName(uri: Uri): String? =
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
