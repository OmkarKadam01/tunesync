package com.tunesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunesync.core.model.ShowStyle

val Ink = Color(0xFF0A1A24)
val Panel = Color(0xFF102632)
val Amber = Color(0xFFFFA61F)
val Cyan = Color(0xFF4FC7E6)
val TextPrimary = Color(0xFFE7EFF3)
val TextMuted = Color(0xFF8DA6B4)
val Warn = Color(0xFFE0A83F)

@Composable
fun EmptyScreen(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "TuneSync",
            color = TextPrimary,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Pick a song and TuneSync maps its beats, then flashes your torch and screen in time with it.",
            color = TextMuted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onPick,
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
        ) {
            Text("Choose a song", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Nothing leaves your phone. No account, no network.",
            color = TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
fun WorkingScreen(stageLabel: String, progress: Float, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stageLabel, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Amber,
            trackColor = Panel,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "${(progress * 100).toInt()}%",
            color = TextMuted,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onCancel) { Text("Cancel", color = TextMuted) }
    }
}

@Composable
fun FailedScreen(message: String, canRetry: Boolean, onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            color = TextPrimary,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onPick,
            colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = TextPrimary),
        ) {
            Text(if (canRetry) "Try another song" else "Choose a different file")
        }
    }
}

@Composable
fun StatRow(tempo: Float, beats: Int, confidence: Float, peakHz: Float) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("Tempo", "%.0f".format(tempo), "bpm")
        Stat("Beats", "$beats", "mapped")
        Stat("Confidence", "%.0f".format(confidence * 100), "%")
        // Designers should be able to see the safety number, not trust a promise.
        Stat("Peak", "%.1f".format(peakHz), "flash/s")
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String) {
    Column {
        Text(label.uppercase(), color = TextMuted, fontSize = 9.sp, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = TextPrimary,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(0.dp))
            Text(" $unit", color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
fun StyleChips(selected: ShowStyle, onSelect: (ShowStyle) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (style in ShowStyle.entries) {
            FilterChip(
                selected = style == selected,
                onClick = { onSelect(style) },
                label = { Text(style.displayName, fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Panel,
                    labelColor = TextMuted,
                    selectedContainerColor = Amber,
                    selectedLabelColor = Ink,
                ),
            )
        }
    }
}

@Composable
fun OutputControls(
    torch: Boolean,
    screen: Boolean,
    gentle: Boolean,
    torchAvailable: Boolean,
    onTorchChange: (Boolean) -> Unit,
    onScreenChange: (Boolean) -> Unit,
    onGentleChange: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Panel)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        OutputToggle(
            title = "Camera flash",
            subtitle = if (torchAvailable) {
                "Pulses the torch on the strong beats."
            } else {
                "This device has no flash."
            },
            checked = torch && torchAvailable,
            enabled = torchAvailable,
            onCheckedChange = onTorchChange,
        )
        Divider()
        OutputToggle(
            title = "Screen",
            subtitle = "Fills the screen with colour on each cue.",
            checked = screen,
            enabled = true,
            onCheckedChange = onScreenChange,
        )
        Divider()
        OutputToggle(
            title = "No strobing",
            subtitle = "Smooth fades only, capped at 1 flash per second.",
            checked = gentle,
            enabled = true,
            onCheckedChange = onGentleChange,
        )

        if (!torch && !screen) {
            Text(
                "Turn on at least one output to run a show.",
                color = Warn,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun OutputToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) TextPrimary else TextMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Amber,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = Ink,
            ),
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.copy(alpha = 0.6f)))
}

@Composable
fun ConsentDialog(onContinue: () -> Unit, onReduced: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = Panel,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted,
        title = { Text("Before you start", fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "This show flashes your camera light and screen in time with the music. " +
                    "Flashing lights can trigger seizures in people with photosensitive epilepsy.\n\n" +
                    "Output is capped at 3 flashes per second, the accessibility limit. " +
                    "Tap anywhere during a show to stop it immediately.",
                fontSize = 14.sp,
            )
        },
        // Equal weight: the safe option is not a secondary action.
        confirmButton = {
            TextButton(onClick = onContinue) { Text("I understand, continue", color = Amber) }
        },
        dismissButton = {
            TextButton(onClick = onReduced) { Text("Use no-flash mode", color = Cyan) }
        },
    )
}

@Composable
fun ShowSurface(active: Boolean, colour: Color, onStop: () -> Unit) {
    if (!active) return
    Box(
        Modifier
            .fillMaxSize()
            .background(colour)
            // Panic stop: any touch anywhere, no confirmation.
            .clickable(onClick = onStop),
    )
}
