package com.tunesync.core.model

import kotlinx.serialization.Serializable

/**
 * The single artefact analysis produces and every downstream subsystem consumes.
 *
 * Deliberately free of Android types, absolute paths and device-specific fields
 * so the same map runs unmodified on another platform or on the operator console.
 */
@Serializable
data class BeatMap(
    val schema: Int = SCHEMA_VERSION,
    /** Content hash of the source audio, not a filename — the file may move. */
    val trackId: String,
    val durationMs: Long,
    /** Sample rate of the *source*, needed for offset arithmetic against playback. */
    val sampleRate: Int,
    val analysis: AnalysisMeta,
    val beats: List<Beat>,
    val sections: List<Section> = emptyList(),
    /** Base64 min/max waveform envelope for the editor, so scrubbing never touches audio. */
    val peaks: String? = null,
    val edits: Edits = Edits(),
) {
    companion object {
        const val SCHEMA_VERSION = 2
    }

    /** Beats surviving the user's mute list, with additions merged and offset applied. */
    fun effectiveBeats(): List<Beat> {
        val muted = edits.muted.toSet()
        val offset = edits.globalOffsetMs
        val kept = beats.asSequence()
            .filter { it.idx !in muted }
            .map { if (offset == 0) it else it.copy(tMs = it.tMs + offset) }
        val added = edits.added.asSequence().map { it.toBeat() }
        return (kept + added).sortedBy { it.tMs }.toList()
    }
}

@Serializable
data class AnalysisMeta(
    /** Bump forces a background re-analysis; user edits survive it. */
    val engineVersion: String,
    val confidence: Float,
    val tempoBpm: Float,
    val tempoStable: Boolean,
    val meterNumerator: Int = 4,
    val meterDenominator: Int = 4,
    val meterConfidence: Float = 0f,
    /** Already applied to beat times; recorded so a wrong trim can be audited. */
    val encoderDelayMs: Float = 0f,
    /** True when the user built the grid by tapping rather than the analyser finding it. */
    val manual: Boolean = false,
) {
    val usable: Boolean get() = manual || confidence >= MIN_USABLE_CONFIDENCE

    companion object {
        /** Below this the analyser refuses rather than shipping a grid that looks right and isn't. */
        const val MIN_USABLE_CONFIDENCE = 0.5f
        const val MIN_METER_CONFIDENCE = 0.6f
    }
}

@Serializable
data class Beat(
    val tMs: Int,
    /** Stable index into the unedited [BeatMap.beats] list; mutes reference this. */
    val idx: Int,
    val bar: Int,
    /** 1-based position within the bar. 1 is the downbeat. */
    val pos: Int,
    /** Combined weight in 0..1 driving which beats become cues. See SalienceScorer. */
    val salience: Float,
    /** Per-band onset strength: kick, snare/clap, hats. Lets a style fire on one drum. */
    val low: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
) {
    val isDownbeat: Boolean get() = pos == 1
}

@Serializable
data class Section(
    val startMs: Int,
    val label: String,
    /** Normalised loudness 0..1, used to scale cue density and brightness. */
    val energy: Float,
)

/**
 * User corrections, kept apart from [BeatMap.beats] so re-analysis with a newer
 * engine can replace the beats without discarding the user's work.
 */
@Serializable
data class Edits(
    val globalOffsetMs: Int = 0,
    val muted: List<Int> = emptyList(),
    val added: List<AddedBeat> = emptyList(),
    val subdivision: Subdivision = Subdivision.AUTO,
    /** Salience threshold override; null means the style decides. */
    val sensitivity: Float? = null,
) {
    val isEmpty: Boolean
        get() = globalOffsetMs == 0 && muted.isEmpty() && added.isEmpty() &&
            subdivision == Subdivision.AUTO && sensitivity == null
}

@Serializable
data class AddedBeat(val tMs: Int, val salience: Float = 1f) {
    fun toBeat() = Beat(tMs = tMs, idx = -1, bar = 0, pos = 0, salience = salience)
}

/** How many beats to skip between cues. AUTO lets the safety limiter choose. */
@Serializable
enum class Subdivision(val step: Int) {
    AUTO(1),
    EVERY_BEAT(1),
    EVERY_2ND(2),
    EVERY_4TH(4),
    DOWNBEATS_ONLY(0),
}
