package com.llama.llamalarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.llama.llamalarm.domain.AppSettings
import com.llama.llamalarm.domain.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val context = LocalContext.current
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    val exactAllowed = rememberExactAlarmPermission()
    val displayPermissions = rememberDisplayPermissions()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader("Snooze options")
            Text(
                "These buttons appear on the alarm screen. Tap to edit any duration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            settings.snoozePresets.forEachIndexed { index, duration ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Snooze ${index + 1}",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { editingIndex = index }) {
                        Text(Format.duration(duration), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            TextButton(onClick = {
                onUpdate { it.copy(snoozePresets = AppSettings.DEFAULT_SNOOZE_PRESETS) }
            }) {
                Text("Reset to defaults")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader("Behavior")
            SwitchRow(
                title = "Vibrate",
                summary = "Vibrate while the alarm rings",
                checked = settings.vibrate,
                onChange = { value -> onUpdate { it.copy(vibrate = value) } },
            )
            SwitchRow(
                title = "Gradual volume",
                summary = "Gently ramp up the alarm volume",
                checked = settings.gradualVolume,
                onChange = { value -> onUpdate { it.copy(gradualVolume = value) } },
            )
            SwitchRow(
                title = "Respect silent mode",
                summary = "Don't play sound in silent/vibrate mode",
                checked = settings.respectSilent,
                onChange = { value -> onUpdate { it.copy(respectSilent = value) } },
            )
            SwitchRow(
                title = "Play through headphones",
                summary = "Route the alarm to connected headphones instead of the speaker",
                checked = settings.respectHeadphones,
                onChange = { value -> onUpdate { it.copy(respectHeadphones = value) } },
            )
            SwitchRow(
                title = "Upcoming alarm notification",
                summary = "Notify 2 hours before the next alarm",
                checked = settings.upcomingNotification,
                onChange = { value -> onUpdate { it.copy(upcomingNotification = value) } },
            )

            Text(
                "Alarm volume: ${(settings.volume * 100).toInt()}%",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = settings.volume,
                onValueChange = { value -> onUpdate { it.copy(volume = value) } },
                valueRange = 0.05f..1f,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader("Permissions")
            PermissionRow(
                title = "Alarms & reminders",
                summary = if (exactAllowed) "Allowed" else "Required for alarms to fire on time",
                action = "Open",
                onClick = { context.startActivity(exactAlarmSettingsIntent(context)) },
            )
            PermissionRow(
                title = "Display over other apps",
                summary = if (displayPermissions.overlay) {
                    "Allowed"
                } else {
                    "Helps the alarm screen appear over other apps"
                },
                action = "Open",
                onClick = { context.startActivity(overlaySettingsIntent(context)) },
            )
            PermissionRow(
                title = "Full-screen notifications",
                summary = if (displayPermissions.fullScreenIntent) {
                    "Allowed"
                } else {
                    "Lets a ringing alarm wake the screen over the lock screen"
                },
                action = "Open",
                onClick = { context.startActivity(fullScreenIntentSettingsIntent(context)) },
            )
            PermissionRow(
                title = "Notifications",
                summary = "Ongoing notification while ringing",
                action = "Open",
                onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                        ).putExtra(
                            android.provider.Settings.EXTRA_APP_PACKAGE,
                            context.packageName,
                        ),
                    )
                },
            )

            Spacer(Modifier.height(40.dp))
        }
    }

    editingIndex?.let { index ->
        val current = settings.snoozePresets.getOrNull(index)
        if (current != null) {
            SnoozeDurationDialog(
                initialMs = current,
                onDismiss = { editingIndex = null },
                onConfirm = { ms ->
                    editingIndex = null
                    onUpdate { s ->
                        s.copy(
                            snoozePresets = s.snoozePresets.toMutableList().also {
                                it[index] = ms
                            },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    summary: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun SnoozeDurationDialog(
    initialMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var hours by remember { mutableStateOf((initialMs / AppSettings.HOUR).toString()) }
    var minutes by remember { mutableStateOf(((initialMs % AppSettings.HOUR) / AppSettings.MINUTE).toString()) }
    val totalMs = (hours.toLongOrNull() ?: 0) * AppSettings.HOUR +
        (minutes.toLongOrNull() ?: 0) * AppSettings.MINUTE
    val valid = totalMs >= AppSettings.MINUTE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Snooze duration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { value -> hours = value.filter(Char::isDigit).take(2) },
                        label = { Text("Hours") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { value -> minutes = value.filter(Char::isDigit).take(2) },
                        label = { Text("Minutes") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = if (valid) "= ${Format.duration(totalMs)}" else "Enter at least one minute",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(totalMs) },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
