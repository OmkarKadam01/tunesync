package com.tunesync.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunesync.core.dsp.LockState
import com.tunesync.core.listen.AlignStatus
import kotlin.math.roundToInt

/**
 * The state of the lock, stated plainly.
 *
 * Three states and a confidence bar, never a faked lock: a listener who cannot
 * tell whether the app is working will assume it is broken, and an app that
 * claims to be locked when it is guessing is worse than one that admits it.
 */
@Composable
fun ListenStatus(status: AlignStatus, running: Boolean) {
    val (label, colour) = when {
        !running -> "Ready to listen" to TextMuted
        status.state == LockState.LOCKED -> "Locked on" to Ok
        status.state == LockState.COASTING -> "Lost the signal — holding on" to Warn
        status.state == LockState.LOST -> "Can't hear the track" to Risk
        status.micSilent -> "Can't hear anything — is the mic covered?" to Warn
        else -> "Listening for the track…" to Cyan
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Panel)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LockDot(colour, pulsing = running && status.state != LockState.LOCKED)
            Spacer(Modifier.size(10.dp))
            Text(label, color = colour, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(14.dp))
        ConfidenceBar(status.confidence, colour)

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Detail("Position", status.positionMs?.let { formatTime(it) } ?: "—")
            // Away from 1.00 means the source is running off-speed, which is worth
            // seeing: it is the difference between drift and a pitched-up DJ set.
            Detail("Rate", if (status.matchesAccepted > 1) "%.3f×".format(status.rate) else "—")
            Detail("Matches", if (status.matchesAccepted > 0) "${status.matchesAccepted}" else "—")
        }

        if (running && !status.unprocessedMic) {
            Spacer(Modifier.height(12.dp))
            Text(
                "This device applies noise processing to the microphone, which can make locking on harder.",
                color = TextMuted,
                fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun LockDot(colour: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "lock")
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "alpha",
        ).value
    } else {
        1f
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(colour.copy(alpha = alpha)))
}

@Composable
private fun ConfidenceBar(confidence: Float, colour: Color) {
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        drawRect(Ink, size = size)
        drawRect(
            colour,
            topLeft = Offset.Zero,
            size = Size(size.width * confidence.coerceIn(0f, 1f), size.height),
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = TextMuted, fontSize = 9.sp, letterSpacing = 1.3.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = TextPrimary,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun MicRationale(onAllow: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Panel)
            .padding(18.dp),
    ) {
        Text("TuneSync needs the microphone", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "To follow music playing around you, the app listens through the microphone " +
                "and works out where in the song you are.\n\n" +
                "Audio is never recorded, never saved and never leaves your phone — only a " +
                "short-lived acoustic fingerprint is compared against the track you imported. " +
                "Listening stops the moment you leave this screen.",
            color = TextMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("Allow microphone", primary = true, onClick = onAllow)
            SmallButton("Not now", primary = false, onClick = onCancel)
        }
    }
}

private fun formatTime(ms: Float): String {
    val total = (ms / 1000f).roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
