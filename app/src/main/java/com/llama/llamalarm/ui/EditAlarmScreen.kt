package com.llama.llamalarm.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llama.llamalarm.domain.Alarm
import com.llama.llamalarm.domain.AlarmDefaults
import com.llama.llamalarm.domain.AlarmType
import com.llama.llamalarm.domain.Format
import com.llama.llamalarm.domain.OccurrenceCalculator
import com.llama.llamalarm.domain.RepeatDays
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditAlarmScreen(
    existing: Alarm?,
    settings: com.llama.llamalarm.domain.AppSettings,
    onBack: () -> Unit,
    onSave: (Alarm, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onImportRingtone: (android.net.Uri, (String, String) -> Unit) -> Unit,
    onClearRingtoneFile: (String?) -> Unit,
) {
    val context = LocalContext.current
    val isNew = existing == null
    val scrollState = rememberScrollState()
    val titleBringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val now = remember { Instant.now() }
    // Start new alarms on a slot that is still ahead today instead of pinning them to tomorrow.
    val defaultStart = remember(now) { AlarmDefaults.startForNewAlarm(now) }

    var hour by remember { mutableIntStateOf(existing?.hour ?: defaultStart.hour) }
    var minute by remember { mutableIntStateOf(existing?.minute ?: defaultStart.minute) }
    var type by remember { mutableStateOf(existing?.type ?: AlarmType.ONE_SHOT) }
    var repeatMask by remember { mutableIntStateOf(existing?.repeatDaysMask ?: 0) }
    fun capitalizeFirst(text: String): String = text.replaceFirstChar { it.uppercaseChar() }

    var label by remember {
        mutableStateOf(capitalizeFirst(existing?.label.orEmpty()))
    }
    // New alarms start from the last ringtone the user chose; editing keeps its own choice.
    var ringtoneUri by remember {
        mutableStateOf(if (existing == null) settings.defaultRingtoneUri else existing.ringtoneUri)
    }
    var ringtoneName by remember {
        mutableStateOf(if (existing == null) settings.defaultRingtoneName else existing.ringtoneName)
    }
    var volumeOverride by remember { mutableStateOf(existing?.volume) }
    var vibrate by remember { mutableStateOf(existing?.vibrate ?: false) }
    var oneShotDate by remember {
        mutableStateOf(existing?.date ?: defaultStart.date)
    }
    // New one-time alarms (and recurring ones without a start date) follow the picked time
    // until a date is chosen manually.
    var dateFollowsTime by remember { mutableStateOf(existing == null || existing.date == null) }
    var pauseStart by remember { mutableStateOf(existing?.pauseStart) }
    var pauseEnd by remember { mutableStateOf(existing?.pauseEnd) }

    fun updateDateForTime(h: Int, m: Int) {
        if (dateFollowsTime) oneShotDate = AlarmDefaults.dateForTime(h, m, now)
    }

    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onClearRingtoneFile(ringtoneUri)
            onImportRingtone(uri) { path, name ->
                ringtoneUri = path
                ringtoneName = name
            }
        }
    }

    // Android's own ringtone picker, showing the longer built-in alarm ringtones.
    val systemSoundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked = result.data
            ?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            ?: return@rememberLauncherForActivityResult
        onClearRingtoneFile(ringtoneUri)
        ringtoneUri = picked.toString()
        ringtoneName = runCatching {
            RingtoneManager.getRingtone(context, picked)?.getTitle(context)
        }.getOrNull() ?: "System sound"
    }

    val draft = remember(
        hour, minute, type, repeatMask, label, ringtoneUri, ringtoneName,
        volumeOverride, vibrate,
        oneShotDate, pauseStart, pauseEnd, existing,
    ) {
        Alarm(
            id = existing?.id ?: "",
            label = label.trim(),
            enabled = existing?.enabled ?: true,
            type = type,
            hour = hour,
            minute = minute,
            dateIso = if (type == AlarmType.ONE_SHOT) {
                oneShotDate.toString()
            } else {
                existing?.dateIso
            },
            repeatDaysMask = repeatMask,
            ringtoneUri = ringtoneUri,
            ringtoneName = ringtoneName,
            volume = volumeOverride,
            vibrate = vibrate,
            skipNextAt = existing?.skipNextAt,
            pauseStartIso = pauseStart?.toString(),
            pauseEndIso = pauseEnd?.toString(),
            createdAt = existing?.createdAt ?: 0,
            updatedAt = existing?.updatedAt ?: 0,
        )
    }

    val oneShotInPast = type == AlarmType.ONE_SHOT &&
        !java.time.LocalDateTime.of(oneShotDate, java.time.LocalTime.of(hour, minute))
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .isAfter(Instant.now())
    val invalid = (type == AlarmType.RECURRING && repeatMask == 0) || oneShotInPast

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New alarm" else "Edit alarm") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isNew && existing != null) {
                        IconButton(onClick = {
                            onClearRingtoneFile(ringtoneUri)
                            onDelete(existing.id)
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                    IconButton(
                        onClick = { if (!invalid) onSave(draft, isNew) },
                        enabled = !invalid,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Time
            Text(
                text = Format.timeOfDay(hour, minute),
                fontSize = 72.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableNoRipple {
                        showTimePicker(context, hour, minute) { h, m ->
                            hour = h
                            minute = m
                            updateDateForTime(h, m)
                        }
                    },
            )
            Text(
                "Tap the time to change it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Type
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = type == AlarmType.RECURRING,
                    onClick = { type = AlarmType.RECURRING },
                    label = { Text("Repeats") },
                )
                FilterChip(
                    selected = type == AlarmType.ONE_SHOT,
                    onClick = {
                        type = AlarmType.ONE_SHOT
                        updateDateForTime(hour, minute)
                    },
                    label = { Text("Once") },
                )
            }

            if (type == AlarmType.RECURRING) {
                Text("Repeat on", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = RepeatDays.contains(repeatMask, day),
                            onClick = { repeatMask = RepeatDays.toggle(repeatMask, day) },
                            label = { Text(day.name.first().toString()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { repeatMask = RepeatDays.allMask() }) { Text("Every day") }
                    TextButton(onClick = { repeatMask = 0 }) { Text("Clear") }
                }
            } else {
                Text("Date", fontWeight = FontWeight.SemiBold)
                OutlinedButton(
                    onClick = {
                        showDatePicker(context, oneShotDate, LocalDate.now()) { picked ->
                            oneShotDate = picked
                            dateFollowsTime = false
                        }
                    },
                ) {
                    Text(oneShotDate.format(Format.dateFull))
                }
                if (oneShotInPast) {
                    Text(
                        "Pick a future date and time.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Label — scroll it into view once the IME has resized the window.
            OutlinedTextField(
                value = label,
                onValueChange = { label = capitalizeFirst(it) },
                label = { Text("Alarm title") },
                placeholder = { Text("e.g. Wake up") },
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(titleBringIntoView)
                    .onFocusEvent { state ->
                        if (state.isFocused) {
                            scope.launch {
                                delay(150)
                                titleBringIntoView.bringIntoView()
                            }
                        }
                    },
            )

            HorizontalDivider()

            // Pause window
            Text("Pause", fontWeight = FontWeight.SemiBold)
            Text(
                text = when {
                    pauseStart != null && pauseEnd != null ->
                        "${pauseStart!!.format(Format.dateMedium)} – ${pauseEnd!!.format(Format.dateMedium)}"

                    else -> "Not paused"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val initial = pauseStart ?: LocalDate.now()
                    showDatePicker(context, initial, LocalDate.now()) { start ->
                        pauseStart = start
                        if (pauseEnd == null || pauseEnd!!.isBefore(start)) pauseEnd = start
                    }
                }) { Text("From") }
                OutlinedButton(onClick = {
                    val start = pauseStart ?: LocalDate.now()
                    val initial = pauseEnd ?: start
                    showDatePicker(context, initial, start) { end ->
                        pauseEnd = end
                    }
                }) { Text("To") }
                if (pauseStart != null || pauseEnd != null) {
                    TextButton(onClick = {
                        pauseStart = null
                        pauseEnd = null
                    }) { Text("Clear") }
                }
            }
            if (type == AlarmType.ONE_SHOT && pauseStart != null) {
                Text(
                    "A paused one-time alarm will not ring if its date falls inside the pause window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            HorizontalDivider()

            // Ringtone
            Text("Ringtone", fontWeight = FontWeight.SemiBold)
            Text(
                text = ringtoneName ?: "Default alarm sound",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose system sound")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            ringtoneUri?.takeIf { it.startsWith("content://") }?.let { uri ->
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(uri))
                            }
                        }
                        systemSoundLauncher.launch(intent)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("System sound")
                }
                OutlinedButton(
                    onClick = { ringtoneLauncher.launch(arrayOf("audio/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Audio file")
                }
            }
            if (ringtoneUri != null) {
                TextButton(onClick = {
                    onClearRingtoneFile(ringtoneUri)
                    ringtoneUri = null
                    ringtoneName = null
                }) {
                    Text("Use default")
                }
            }

            HorizontalDivider()

            // Alarm options (per-alarm overrides)
            Text("Alarm options", fontWeight = FontWeight.SemiBold)
            val effectiveVolume = volumeOverride ?: settings.volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Volume ${(effectiveVolume * 100).toInt()}%",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (volumeOverride != null) {
                    TextButton(onClick = { volumeOverride = null }) { Text("Use default") }
                }
            }
            androidx.compose.material3.Slider(
                value = effectiveVolume,
                onValueChange = { volumeOverride = it },
                valueRange = 0.05f..1f,
            )

            Text(
                "Vibrate",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = vibrate,
                    onClick = { vibrate = true },
                    label = { Text("On") },
                    shape = CircleShape,
                )
                FilterChip(
                    selected = !vibrate,
                    onClick = { vibrate = false },
                    label = { Text("Off") },
                    shape = CircleShape,
                )
            }

            HorizontalDivider()

            val next = if (draft.enabled) {
                OccurrenceCalculator.nextOccurrence(draft, Instant.now())
            } else {
                null
            }
            Text(
                text = "Next ring: " + (next?.let { instant ->
                    val local = instant.atZone(java.time.ZoneId.systemDefault())
                    local.format(Format.dateFull) + " at " +
                        Format.timeOfDay(local.hour, local.minute) +
                        " (" + Format.relativeToNow(instant) + ")"
                } ?: "none"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    composed {
        clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    }
