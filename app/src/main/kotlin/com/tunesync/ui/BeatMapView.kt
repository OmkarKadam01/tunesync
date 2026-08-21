package com.tunesync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tunesync.audio.WaveformPeaks
import com.tunesync.core.model.BeatMap
import com.tunesync.core.model.Channel
import com.tunesync.core.safety.ArmedShow

private val WaveColor = Color(0xFF6C8494)
private val BeatColor = Color(0xFF4FC7E6)
private val DownbeatColor = Color(0xFFFFFFFF)
private val CueColor = Color(0xFFFFA61F)
private val PlayheadColor = Color(0xFFFFFFFF)

/**
 * Three lanes over one timebase: what the audio looks like, where the beats are,
 * and what the phone will actually do. Showing the third lane is the point — it
 * makes the mapping visible instead of asserted, so the sensitivity slider's
 * effect is legible before the user commits to a show.
 */
@Composable
fun BeatMapView(
    map: BeatMap,
    peaks: WaveformPeaks,
    show: ArmedShow,
    positionMs: Float,
    modifier: Modifier = Modifier,
) {
    val durationMs = map.durationMs.coerceAtLeast(1L).toFloat()

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            val cols = peaks.columns
            if (cols == 0) return@Canvas
            val barW = (w / cols).coerceAtLeast(0.7f)

            for (c in 0 until cols) {
                val x = c * w / cols
                val hi = peaks.max[c].coerceIn(0f, 1f) * mid
                // Negate before clamping. `-x.coerceIn(0f, 1f)` binds as
                // `-(x.coerceIn(...))`, and min is negative, so it clamps to zero
                // and the lower half of the waveform never draws.
                val lo = (-peaks.min[c]).coerceIn(0f, 1f) * mid
                drawRect(
                    color = WaveColor,
                    topLeft = Offset(x, mid - hi),
                    size = Size(barW, (hi + lo).coerceAtLeast(0.7f)),
                )
            }

            val px = (positionMs / durationMs).coerceIn(0f, 1f) * w
            drawRect(PlayheadColor, Offset(px, 0f), Size(1.5f, h))
        }

        Spacer(Modifier.height(6.dp))

        // Beat lane: tick height is salience. Wobble in the spacing is far easier
        // to see here than to hear, which is what makes a bad grid obvious.
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val w = size.width
            val h = size.height
            for (b in map.beats) {
                val x = (b.tMs / durationMs).coerceIn(0f, 1f) * w
                val tickH = (0.25f + 0.75f * b.salience) * h
                drawRect(
                    color = if (b.isDownbeat) DownbeatColor else BeatColor,
                    topLeft = Offset(x, h - tickH),
                    size = Size(if (b.isDownbeat) 2f else 1.2f, tickH),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Cue lane: real pulse widths, so the show reads as duration not just position.
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val w = size.width
            val h = size.height
            for (cue in show.cues) {
                if (cue.channel == Channel.HAPTIC) continue
                val x = (cue.timeMs / durationMs).coerceIn(0f, 1f) * w
                val cw = ((cue.durationMs / durationMs) * w).coerceAtLeast(1.5f)
                val isTorch = cue.channel == Channel.TORCH
                drawRect(
                    color = if (isTorch) CueColor else CueColor.copy(alpha = 0.35f),
                    topLeft = Offset(x, if (isTorch) 0f else h * 0.55f),
                    size = Size(cw, if (isTorch) h * 0.5f else h * 0.45f),
                )
            }
        }
    }
}
