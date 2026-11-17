package com.llama.llamalarm.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.llama.llamalarm.domain.Alarm
import com.llama.llamalarm.domain.Format
import com.llama.llamalarm.domain.OccurrenceCalculator
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    alarms: List<Alarm>,
    canScheduleExact: Boolean,
    onOpenSettings: () -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onSkipNext: (String) -> Unit,
    onPause: (Alarm) -> Unit,
    onClearPause: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRequestExactAlarmSettings: () -> Unit,
) {
    val context = LocalContext.current
    val displayPermissions = rememberDisplayPermissions()
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Llamalarm") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "New alarm")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (!canScheduleExact) {
                PermissionBanner(onClick = onRequestExactAlarmSettings)
            }
            if (!displayPermissions.overlay || !displayPermissions.fullScreenIntent) {
                DisplayPermissionBanner(
                    overlayAllowed = displayPermissions.overlay,
                    fullScreenIntentAllowed = displayPermissions.fullScreenIntent,
                    onOpenOverlaySettings = {
                        runCatching { context.startActivity(overlaySettingsIntent(context)) }
                    },
                    onOpenFullScreenIntentSettings = {
                        runCatching { context.startActivity(fullScreenIntentSettingsIntent(context)) }
                    },
                )
            }
            if (alarms.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(48.dp))
                    Text("No alarms yet", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to create your first alarm.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmCard(
                            alarm = alarm,
                            onClick = { onEdit(alarm.id) },
                            onToggle = { onToggle(alarm.id, it) },
                            onSkipNext = { onSkipNext(alarm.id) },
                            onPause = { onPause(alarm) },
                            onClearPause = { onClearPause(alarm.id) },
                            onDelete = { onDelete(alarm.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayPermissionBanner(
    overlayAllowed: Boolean,
    fullScreenIntentAllowed: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onOpenFullScreenIntentSettings: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Let the alarm take over the screen", fontWeight = FontWeight.SemiBold)
            Text(
                "Without these, a ringing alarm may only appear as a notification.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!overlayAllowed) {
                TextButton(onClick = onOpenOverlaySettings) {
                    Text("Allow display over other apps")
                }
            }
            if (!fullScreenIntentAllowed) {
                TextButton(onClick = onOpenFullScreenIntentSettings) {
                    Text("Allow full-screen notifications")
                }
            }
        }
    }
}

@Composable
private fun PermissionBanner(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Exact alarms are disabled", fontWeight = FontWeight.SemiBold)
            Text(
                "Allow alarms & reminders so llamalarm can ring on time.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onClick) { Text("Open settings") }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSkipNext: () -> Unit,
    onPause: () -> Unit,
    onClearPause: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val paused = alarm.pauseEnd?.let { !it.isBefore(today) } == true

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.enabled) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Format.timeOfDay(alarm.hour, alarm.minute),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light,
                    color = if (alarm.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = listOfNotNull(
                        alarm.scheduleSummary(),
                        alarm.label.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val nextLine = when {
                    !alarm.enabled -> "Off"
                    paused -> "Paused until " + alarm.pauseEnd?.format(Format.dateMedium)
                    alarm.skipNextAt != null -> "Next occurrence skipped"
                    else -> {
                        val next = OccurrenceCalculator.nextOccurrence(alarm, Instant.now())
                        next?.let { "Next " + Format.relativeToNow(it) } ?: "No upcoming occurrence"
                    }
                }
                Text(
                    text = nextLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (paused) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(checked = alarm.enabled, onCheckedChange = onToggle)

            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        menuOpen = false
                        onClick()
                    },
                )
                if (alarm.enabled && !paused) {
                    DropdownMenuItem(
                        text = { Text("Skip next occurrence") },
                        onClick = {
                            menuOpen = false
                            onSkipNext()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (paused) "Change pause" else "Pause…") },
                    onClick = {
                        menuOpen = false
                        onPause()
                    },
                )
                if (paused) {
                    DropdownMenuItem(
                        text = { Text("Resume (clear pause)") },
                        onClick = {
                            menuOpen = false
                            onClearPause()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
fun rememberExactAlarmPermission(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allowed by remember {
        mutableStateOf(
            context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms(),
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = context.getSystemService(android.app.AlarmManager::class.java)
                    .canScheduleExactAlarms()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return allowed
}

data class DisplayPermissions(
    val overlay: Boolean,
    val fullScreenIntent: Boolean,
)

fun currentDisplayPermissions(context: Context): DisplayPermissions {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    return DisplayPermissions(
        overlay = Settings.canDrawOverlays(context),
        fullScreenIntent = notificationManager?.canUseFullScreenIntent() ?: true,
    )
}

@Composable
fun rememberDisplayPermissions(): DisplayPermissions {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissions by remember { mutableStateOf(currentDisplayPermissions(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = currentDisplayPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return permissions
}

fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

fun overlaySettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

fun fullScreenIntentSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
