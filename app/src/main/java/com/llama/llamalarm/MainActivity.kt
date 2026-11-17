package com.llama.llamalarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llama.llamalarm.domain.Alarm
import com.llama.llamalarm.domain.Format
import com.llama.llamalarm.ui.AlarmListScreen
import com.llama.llamalarm.ui.EditAlarmScreen
import com.llama.llamalarm.ui.MainViewModel
import com.llama.llamalarm.ui.SettingsScreen
import com.llama.llamalarm.ui.exactAlarmSettingsIntent
import com.llama.llamalarm.ui.rememberExactAlarmPermission
import com.llama.llamalarm.ui.showDatePicker
import com.llama.llamalarm.ui.theme.LlamalarmTheme
import java.time.LocalDate

private enum class Screen { LIST, EDIT, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LlamalarmTheme {
                LlamalarmRoot()
            }
        }
    }
}

@Composable
private fun LlamalarmRoot() {
    val vm: MainViewModel = viewModel()
    val alarms by vm.alarms.collectAsState()
    val settings by vm.settings.collectAsState()
    val canScheduleExact = rememberExactAlarmPermission()
    val context = androidx.compose.ui.platform.LocalContext.current

    var screen by rememberSaveable { mutableStateOf(Screen.LIST) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pauseTarget by remember { mutableStateOf<Alarm?>(null) }

    BackHandler(enabled = screen != Screen.LIST) {
        screen = Screen.LIST
        editingId = null
    }

    when (screen) {
        Screen.LIST -> AlarmListScreen(
            alarms = alarms,
            canScheduleExact = canScheduleExact,
            onOpenSettings = { screen = Screen.SETTINGS },
            onEdit = { id ->
                editingId = id
                screen = Screen.EDIT
            },
            onAdd = {
                editingId = null
                screen = Screen.EDIT
            },
            onToggle = vm::setEnabled,
            onSkipNext = vm::skipNext,
            onPause = { pauseTarget = it },
            onClearPause = vm::clearPause,
            onDelete = vm::deleteAlarm,
            onRequestExactAlarmSettings = {
                runCatching { context.startActivity(exactAlarmSettingsIntent(context)) }
            },
        )

        Screen.EDIT -> {
            val existing = editingId?.let { id -> alarms.firstOrNull { it.id == id } }
            EditAlarmScreen(
                existing = existing,
                settings = settings,
                onBack = {
                    screen = Screen.LIST
                    editingId = null
                },
                onSave = { alarm, isNew ->
                    vm.saveAlarm(alarm, isNew)
                    screen = Screen.LIST
                    editingId = null
                },
                onDelete = { id ->
                    vm.deleteAlarm(id)
                    screen = Screen.LIST
                    editingId = null
                },
                onImportRingtone = vm::importRingtone,
                onClearRingtoneFile = vm::clearImportedRingtone,
            )
        }

        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            onBack = { screen = Screen.LIST },
            onUpdate = vm::updateSettings,
        )
    }

    pauseTarget?.let { alarm ->
        PauseDialog(
            alarm = alarm,
            onDismiss = { pauseTarget = null },
            onConfirm = { start, end ->
                vm.pause(alarm.id, start, end)
                pauseTarget = null
            },
        )
    }
}

@Composable
private fun PauseDialog(
    alarm: Alarm,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var start by remember { mutableStateOf(alarm.pauseStart ?: LocalDate.now()) }
    var end by remember { mutableStateOf(alarm.pauseEnd ?: LocalDate.now().plusDays(6)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pause alarm") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Any occurrence in this range is skipped. The schedule resumes afterwards.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = {
                        showDatePicker(context, start, LocalDate.now()) { picked ->
                            start = picked
                            if (end.isBefore(picked)) end = picked
                        }
                    },
                ) {
                    Text("From: " + start.format(Format.dateFull))
                }
                OutlinedButton(
                    onClick = {
                        showDatePicker(context, end, start) { picked -> end = picked }
                    },
                ) {
                    Text("To: " + end.format(Format.dateFull))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(start, end) }) { Text("Pause") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
