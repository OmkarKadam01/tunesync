package com.tunesync.core.model

import kotlin.math.ceil
import kotlin.math.roundToInt

enum class ShowStyle(val displayName: String, val description: String) {
    PULSE("Pulse", "Flashes on the strong beats. Works for almost anything."),
    ANCHOR("Anchor", "Downbeats only, held longer. Suits slow songs and anthems."),
    KICK("Kick", "Follows the kick drum, ignoring the beat grid."),
    SWELL("Swell", "No flashing — the screen breathes with the music."),
    SECTIONS("Sections", "Colour changes with the song; intensity follows the energy."),
}

data class CompileOptions(
    /** Cues are scheduled this much early so light lands on the beat, not after it. */
    val latencyCompensationMs: Float = 0f,
    val enableTorch: Boolean = true,
    val enableScreen: Boolean = true,
    val enableHaptics: Boolean = false,
    /** Overrides the style's salience threshold when set. Lower means more cues. */
    val sensitivity: Float? = null,
    val subdivision: Subdivision = Subdivision.AUTO,
    /**
     * Force every screen cue to rise gradually. This is what makes no-flash mode
     * genuinely flash-free: a DECAY cue still switches on instantly and counts
     * against the ceiling, only a RAMP does not.
     */
    val forceSmooth: Boolean = false,
    /** Rate the AUTO subdivision aims to stay under, leaving the limiter idle. */
    val targetRateHz: Float = 2.5f,
    val torchPulseMs: Int = 60,
    val screenColor: Int = 0xFFE8B0,
)

/**
 * Turns a beat map plus a style into a flat, sorted cue list.
 *
 * Compilation happens once, ahead of time, and is deterministic: the same map,
 * style and options always produce the same bytes. The runtime never makes a
 * creative decision at three milliseconds' notice — that is what makes the
 * timing budget achievable and the show testable.
 */
object ShowCompiler {

    fun compile(map: BeatMap, style: ShowStyle, options: CompileOptions = CompileOptions()): UnsafeCueList {
        val beats = map.effectiveBeats()
        if (beats.isEmpty()) return UnsafeCueList(map.trackId, map.durationMs, emptyList())

        val requested = if (options.subdivision != Subdivision.AUTO) {
            options.subdivision
        } else {
            map.edits.subdivision
        }
        val step = resolveSubdivision(map, options)
        val threshold = options.sensitivity ?: map.edits.sensitivity ?: style.defaultThreshold()
        val selected = select(
            beats, style, step, threshold,
            downbeatsOnly = requested == Subdivision.DOWNBEATS_ONLY,
        )

        val cues = ArrayList<Cue>(selected.size * 2)
        for (beat in selected) {
            val t = beat.tMs - options.latencyCompensationMs
            val timeMs = t.roundToInt().coerceAtLeast(0)

            if (options.enableTorch && style != ShowStyle.SWELL) {
                cues += Cue(
                    timeMs = timeMs,
                    channel = Channel.TORCH,
                    durationMs = torchDuration(style, beat, options),
                    strength = torchStrength(beat),
                )
            }
            if (options.enableScreen) {
                cues += Cue(
                    timeMs = timeMs,
                    channel = Channel.SCREEN,
                    durationMs = screenDuration(style, beat, map),
                    strength = beat.salience.coerceIn(0.25f, 1f),
                    rgb = options.screenColor,
                    curve = if (options.forceSmooth || style == ShowStyle.SWELL) {
                        Curve.RAMP
                    } else {
                        Curve.DECAY
                    },
                )
            }
            if (options.enableHaptics && beat.isDownbeat) {
                cues += Cue(timeMs, Channel.HAPTIC, durationMs = 25, strength = beat.salience)
            }
        }

        cues.sortBy { it.timeMs }
        return UnsafeCueList(map.trackId, map.durationMs, cues)
    }

    private fun select(
        beats: List<Beat>,
        style: ShowStyle,
        step: Int,
        threshold: Float,
        downbeatsOnly: Boolean,
    ): List<Beat> = when {
        // DOWNBEATS_ONLY has step 0, which every stride expression below would
        // either divide by or silently coerce to 1 — it has to be handled as its
        // own case rather than as a stride.
        downbeatsOnly || style == ShowStyle.ANCHOR -> beats.filter { it.isDownbeat }
        style == ShowStyle.KICK -> beats.filter { it.low >= threshold }
        else -> beats.filterIndexed { i, b -> i % step == 0 && b.salience >= threshold }
    }

    /**
     * How many beats to skip between cues.
     *
     * AUTO picks the smallest step that keeps the flash train under the target
     * rate, so the safety limiter stays an exception path rather than the normal
     * one. 3 Hz is exactly 180 bpm, so this starts mattering around drum and bass
     * tempos and above.
     */
    private fun resolveSubdivision(map: BeatMap, options: CompileOptions): Int {
        val explicit = if (options.subdivision != Subdivision.AUTO) {
            options.subdivision
        } else {
            map.edits.subdivision
        }
        if (explicit != Subdivision.AUTO) return explicit.step.coerceAtLeast(1)

        val beatsPerSecond = map.analysis.tempoBpm / 60f
        if (beatsPerSecond <= 0f) return 1
        return ceil(beatsPerSecond / options.targetRateHz).toInt().coerceIn(1, 8)
    }

    private fun ShowStyle.defaultThreshold(): Float = when (this) {
        ShowStyle.PULSE -> 0.55f
        ShowStyle.ANCHOR -> 0f
        ShowStyle.KICK -> 0.5f
        ShowStyle.SWELL -> 0.3f
        ShowStyle.SECTIONS -> 0.5f
    }

    private fun torchDuration(style: ShowStyle, beat: Beat, options: CompileOptions): Int = when (style) {
        ShowStyle.ANCHOR -> 110
        // Below Android 13 there is no graded brightness, so a longer pulse is the
        // only way to make a strong beat read as brighter.
        else -> (options.torchPulseMs * (0.7f + 0.6f * beat.salience)).roundToInt().coerceIn(25, 160)
    }

    private fun torchStrength(beat: Beat): Float = (0.45f + 0.55f * beat.salience).coerceIn(0.2f, 1f)

    private fun screenDuration(style: ShowStyle, beat: Beat, map: BeatMap): Int {
        val beatMs = if (map.analysis.tempoBpm > 0) (60_000f / map.analysis.tempoBpm) else 500f
        return when (style) {
            ShowStyle.SWELL -> (beatMs * 1.6f).roundToInt()
            ShowStyle.ANCHOR -> (beatMs * map.analysis.meterNumerator).roundToInt()
            else -> (beatMs * 0.55f).roundToInt().coerceAtLeast(40)
        }
    }
}
