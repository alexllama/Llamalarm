package com.llama.llamalarm.ringing

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llama.llamalarm.domain.Alarm
import com.llama.llamalarm.domain.Format
import com.llama.llamalarm.domain.RingInstance
import com.llama.llamalarm.domain.RingPhase
import com.llama.llamalarm.domain.RingStateMachine

@Composable
fun RingingScreen(
    alarm: Alarm,
    instance: RingInstance?,
    presets: List<Long>,
    onStop: () -> Unit,
    onSnooze: (Long) -> Unit,
) {
    val canSnooze = instance == null ||
        (instance.phase == RingPhase.RINGING && instance.userSnoozeCount < RingStateMachine.MAX_USER_SNOOZES)
    val snoozeCount = instance?.userSnoozeCount ?: 0
    // In landscape the fixed-height content is often taller than the screen, so make it scrollable.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                ),
            ),
    ) {
        val contentModifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .then(if (isLandscape) Modifier.verticalScroll(scrollState) else Modifier)
        Column(
            modifier = contentModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Alarm time — keep AM/PM in its own column so four-digit times never overlap it.
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = Format.timeDigits(alarm.hour, alarm.minute),
                    fontSize = 88.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = Format.amPm(alarm.hour),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                text = "Alarm",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))

            // Snooze options, two columns
            val chunks = presets.chunked(2)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                chunks.forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rowPresets.forEach { duration ->
                            FilledTonalButton(
                                onClick = { onSnooze(duration) },
                                enabled = canSnooze,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(104.dp),
                                shape = RoundedCornerShape(24.dp),
                            ) {
                                Text(
                                    text = Format.durationShort(duration),
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            }
                        }
                        if (rowPresets.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (!canSnooze && snoozeCount >= RingStateMachine.MAX_USER_SNOOZES) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Snooze limit reached — stop to dismiss",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // In portrait this flexible spacer pins the title/Stop to the bottom. In landscape
            // the column is scrollable, so a weight would be unbounded — use a fixed gap instead.
            if (isLandscape) {
                Spacer(Modifier.height(24.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }

            // Alarm title, up to three lines
            if (alarm.label.isNotBlank()) {
                Text(
                    text = alarm.label,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(20.dp))
            }

            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Stop", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
