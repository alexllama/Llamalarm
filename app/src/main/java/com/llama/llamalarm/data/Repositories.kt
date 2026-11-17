package com.llama.llamalarm.data

import com.llama.llamalarm.domain.Alarm
import com.llama.llamalarm.domain.AppSettings
import com.llama.llamalarm.domain.OccurrenceCalculator
import com.llama.llamalarm.domain.RingInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Serializable
data class AlarmList(val alarms: List<Alarm> = emptyList())

@Serializable
data class InstanceList(val instances: List<RingInstance> = emptyList())

class AlarmRepository(
    private val store: JsonStore<AlarmList>,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    init {
        // Small JSON file; load synchronously so scheduling on app start sees the alarms.
        _alarms.value = store.read().alarms
    }

    fun get(id: String): Alarm? = _alarms.value.firstOrNull { it.id == id }

    suspend fun upsert(alarm: Alarm): Alarm = mutex.withLock {
        val now = System.currentTimeMillis()
        val normalized = alarm.copy(
            updatedAt = now,
            createdAt = if (alarm.createdAt == 0L) now else alarm.createdAt,
        )
        val next = _alarms.value.filterNot { it.id == normalized.id } + normalized
        store.write(AlarmList(next))
        _alarms.value = next
        normalized
    }

    suspend fun delete(id: String) = mutex.withLock {
        val next = _alarms.value.filterNot { it.id == id }
        store.write(AlarmList(next))
        _alarms.value = next
    }

    suspend fun create(alarm: Alarm): Alarm = upsert(alarm.copy(id = UUID.randomUUID().toString()))

    suspend fun setEnabled(id: String, enabled: Boolean) {
        get(id)?.let { upsert(it.copy(id = it.id, enabled = enabled)) }
    }

    /** Skips the next scheduled occurrence by pinning its trigger time. */
    suspend fun skipNext(id: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        val alarm = get(id) ?: return
        val next = OccurrenceCalculator.nextOccurrenceIgnoringSkip(alarm, now, zone) ?: return
        upsert(alarm.copy(skipNextAt = next.toEpochMilli()))
    }

    suspend fun pause(id: String, start: LocalDate, end: LocalDate) {
        val alarm = get(id) ?: return
        if (end.isBefore(start)) return
        upsert(alarm.copy(pauseStartIso = start.toString(), pauseEndIso = end.toString()))
    }

    suspend fun clearPause(id: String) {
        get(id)?.let { upsert(it.copy(pauseStartIso = null, pauseEndIso = null)) }
    }

    /** Updates the cached next trigger. Ignored when the alarm was deleted concurrently. */
    suspend fun updateNextTrigger(id: String, nextTriggerAt: Long?, zone: ZoneId = ZoneId.systemDefault()) {
        val alarm = get(id) ?: return
        if (alarm.nextTriggerAt == nextTriggerAt) return
        // Don't overwrite user edits made while we were computing.
        upsert(alarm.copy(nextTriggerAt = nextTriggerAt))
    }

    /**
     * Called after an instance resolves. For one-shot alarms the alarm is disabled; recurring
     * alarms clear the consumed skip marker if it was the skipped occurrence.
     */
    suspend fun onInstanceResolved(id: String, instanceTriggerAt: Long) {
        val alarm = get(id) ?: return
        var updated = alarm
        if (alarm.type == com.llama.llamalarm.domain.AlarmType.ONE_SHOT) {
            updated = updated.copy(enabled = false)
        }
        if (alarm.skipNextAt == instanceTriggerAt) {
            updated = updated.copy(skipNextAt = null)
        }
        if (updated != alarm) upsert(updated)
    }
}

class SettingsRepository(
    private val store: JsonStore<AppSettings>,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        _settings.value = store.read()
    }

    fun current(): AppSettings = _settings.value

    suspend fun update(transform: (AppSettings) -> AppSettings) = mutex.withLock {
        val next = transform(_settings.value)
        withContext(Dispatchers.IO) { store.write(next) }
        _settings.value = next
    }
}

class InstanceRepository(
    private val store: JsonStore<InstanceList>,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _instances = MutableStateFlow<List<RingInstance>>(emptyList())
    val instances: StateFlow<List<RingInstance>> = _instances.asStateFlow()

    init {
        _instances.value = store.read().instances
    }

    fun forAlarm(alarmId: String): RingInstance? =
        _instances.value.firstOrNull { it.alarmId == alarmId }

    fun ringing(): List<RingInstance> = _instances.value.filter { it.isRinging }

    suspend fun put(instance: RingInstance) = mutex.withLock {
        val next = _instances.value.filterNot { it.alarmId == instance.alarmId } + instance
        store.write(InstanceList(next))
        _instances.value = next
    }

    suspend fun remove(alarmId: String) = mutex.withLock {
        val next = _instances.value.filterNot { it.alarmId == alarmId }
        if (next.size != _instances.value.size) store.write(InstanceList(next))
        _instances.value = next
    }

    suspend fun replaceAll(instances: List<RingInstance>) = mutex.withLock {
        store.write(InstanceList(instances))
        _instances.value = instances
    }
}
