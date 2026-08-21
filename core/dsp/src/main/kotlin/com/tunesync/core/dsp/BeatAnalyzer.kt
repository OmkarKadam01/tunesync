package com.tunesync.core.dsp

import com.tunesync.core.model.AnalysisMeta
import com.tunesync.core.model.Beat
import com.tunesync.core.model.BeatMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Stages reported to the UI so analysis streams instead of showing a spinner. */
enum class AnalysisStage(val label: String) {
    DECODING("Reading the file"),
    SPECTROGRAM("Building the spectrogram"),
    SEPARATING("Separating drums from vocals"),
    ONSETS("Finding transients"),
    TEMPO("Estimating tempo"),
    BEATS("Tracking beats"),
    SCORING("Scoring beats"),
    DONE("Done"),
}

sealed interface AnalysisResult {
    data class Success(val map: BeatMap, val elapsedMs: Long) : AnalysisResult

    /** The analyser refusing rather than shipping a grid that looks right and isn't. */
    data class NoBeat(val reason: String, val confidence: Float) : AnalysisResult

    data class Silent(val peakDbfs: Float) : AnalysisResult
}

/**
 * Runs the whole pipeline and produces the one artefact everything downstream
 * consumes. Pure computation: no Android types, no I/O, host-testable.
 */
object BeatAnalyzer {

    const val ENGINE_VERSION = "1.0.0"

    /** Below this the file has no audible content worth analysing. */
    private const val SILENCE_PEAK_DBFS = -50f

    /**
     * Share of spectral energy that must survive separation. Measured: a pure
     * vibrato'd vocal keeps 0.7%, a drum pattern keeps 98%. Real music with both
     * sits well above this floor.
     */
    private const val MIN_PERCUSSIVE_RATIO = 0.05f

    private fun energyRatio(part: Spectrogram, whole: Spectrogram): Float {
        var num = 0.0
        var den = 0.0
        for (i in whole.data.indices) {
            num += part.data[i]
            den += whole.data[i]
        }
        return if (den > 1e-9) (num / den).toFloat() else 0f
    }

    fun analyze(
        signal: AudioSignal,
        trackId: String,
        encoderDelayMs: Float = 0f,
        onProgress: (AnalysisStage, Float) -> Unit = { _, _ -> },
    ): AnalysisResult {
        val started = System.nanoTime()

        val peak = signal.peak()
        val peakDb = if (peak <= 0f) -120f else (20.0 * kotlin.math.log10(peak.toDouble())).toFloat()
        if (peakDb < SILENCE_PEAK_DBFS) return AnalysisResult.Silent(peakDb)

        onProgress(AnalysisStage.SPECTROGRAM, 0f)
        val analysis = signal.resampleTo(AudioSignal.ANALYSIS_RATE)
        val spec = Stft.compute(analysis)

        onProgress(AnalysisStage.SEPARATING, 0.25f)
        val percussive = Hpss.percussive(spec)

        // Absolute gate, before anything scale-free runs.
        //
        // The onset envelope is normalised to unit variance, so a track with no
        // percussion at all still produces a full-scale envelope built entirely
        // out of numerical noise — and a beat tracker will happily fit a perfectly
        // regular grid to noise and report high confidence for it. Structure alone
        // cannot catch that; only measuring how much percussive energy exists can.
        val percussiveRatio = energyRatio(percussive, spec)
        if (percussiveRatio < MIN_PERCUSSIVE_RATIO) {
            return AnalysisResult.NoBeat(
                "There's no percussion in this track for us to follow.",
                0f,
            )
        }

        onProgress(AnalysisStage.ONSETS, 0.55f)
        val env = OnsetDetector.detect(percussive)

        onProgress(AnalysisStage.TEMPO, 0.70f)
        val tempo = TempoEstimator.estimate(env)

        onProgress(AnalysisStage.BEATS, 0.80f)
        val beatFrames = BeatTracker.track(env, tempo)
        if (beatFrames.size < 4) {
            return AnalysisResult.NoBeat("No steady pulse in this track.", 0f)
        }

        onProgress(AnalysisStage.SCORING, 0.92f)
        val meter = DownbeatDetector.detect(env, beatFrames)
        val scored = SalienceScorer.score(env, spec, beatFrames, meter)
        val confidence = confidence(env, tempo, beatFrames)

        if (confidence < AnalysisMeta.MIN_USABLE_CONFIDENCE) {
            return AnalysisResult.NoBeat(
                "We couldn't find a steady beat in this track.",
                confidence,
            )
        }

        val beats = ArrayList<Beat>(beatFrames.size)
        for (i in beatFrames.indices) {
            val tMs = (env.frameTimeMs(beatFrames[i]) - encoderDelayMs).roundToInt().coerceAtLeast(0)
            val posInBar = if (meter.usable) (i - meter.phase).mod(meter.beatsPerBar) + 1 else 0
            val bar = if (meter.usable) (i - meter.phase).floorDiv(meter.beatsPerBar) + 1 else 0
            beats += Beat(
                tMs = tMs,
                idx = i,
                bar = bar,
                pos = posInBar,
                salience = scored.salience[i],
                low = scored.low[i],
                mid = scored.mid[i],
                high = scored.high[i],
            )
        }

        onProgress(AnalysisStage.DONE, 1f)
        val map = BeatMap(
            trackId = trackId,
            durationMs = signal.durationMs,
            sampleRate = signal.sampleRate,
            analysis = AnalysisMeta(
                engineVersion = ENGINE_VERSION,
                confidence = confidence,
                tempoBpm = tempo.bpm,
                tempoStable = tempo.stable,
                meterNumerator = meter.beatsPerBar,
                meterConfidence = meter.confidence,
                encoderDelayMs = encoderDelayMs,
            ),
            beats = beats,
        )
        return AnalysisResult.Success(map, (System.nanoTime() - started) / 1_000_000)
    }

    /**
     * How much to trust this grid. A bad grid that pretends to be good is worse
     * than an honest refusal, so this feeds a hard threshold rather than only a
     * display value.
     */
    private fun confidence(env: OnsetEnvelope, tempo: TempoEstimate, beats: IntArray): Float {
        if (beats.size < 4) return 0f

        // How regular the tracked intervals actually are.
        var mean = 0f
        for (i in 1 until beats.size) mean += (beats[i] - beats[i - 1]).toFloat()
        mean /= (beats.size - 1)
        var varSum = 0f
        for (i in 1 until beats.size) {
            val d = (beats[i] - beats[i - 1]) - mean
            varSum += d * d
        }
        val cv = if (mean > 0f) sqrt(varSum / (beats.size - 1)) / mean else 1f
        val regularity = (1f - cv * 4f).coerceIn(0f, 1f)

        // How much onset energy actually lands on the beats we chose, relative to
        // the track's average. A grid laid over noise scores near 1.0 here.
        var onBeat = 0f
        for (f in beats) onBeat += env.total[f]
        onBeat /= beats.size
        var overall = 0f
        for (v in env.total) overall += v
        overall /= env.frames.coerceAtLeast(1)
        val support = if (overall > 1e-6f) ((onBeat / overall - 1f) / 1.5f).coerceIn(0f, 1f) else 0f

        return (0.40f * regularity + 0.35f * support + 0.25f * tempo.confidence).coerceIn(0f, 1f)
    }

    /** Half-time / double-time correction, exposed as the editor's one-tap fix. */
    fun rescale(map: BeatMap, factor: Float): BeatMap {
        require(factor == 0.5f || factor == 2f) { "only halving and doubling are supported" }
        val src = map.beats
        val out = ArrayList<Beat>(if (factor == 2f) src.size * 2 else src.size / 2 + 1)
        if (factor == 0.5f) {
            for (i in src.indices step 2) out += src[i].copy(idx = out.size)
        } else {
            for (i in src.indices) {
                out += src[i].copy(idx = out.size)
                if (i + 1 < src.size) {
                    val mid = (src[i].tMs + src[i + 1].tMs) / 2
                    out += Beat(
                        tMs = mid,
                        idx = out.size,
                        bar = src[i].bar,
                        pos = 0,
                        // An interpolated beat is not an observed one; score it low
                        // so styles do not treat it as a hit.
                        salience = src[i].salience * 0.4f,
                    )
                }
            }
        }
        val meter = map.analysis.meterNumerator
        val renumbered = out.mapIndexed { i, b ->
            b.copy(bar = i / meter + 1, pos = i % meter + 1)
        }
        return map.copy(
            beats = renumbered,
            analysis = map.analysis.copy(tempoBpm = map.analysis.tempoBpm * factor),
        )
    }

    /** Mean absolute error in ms between two beat grids, for the test corpus. */
    fun meanAbsoluteError(a: List<Int>, b: List<Int>): Float {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE
        var acc = 0f
        for (t in a) {
            var best = Int.MAX_VALUE
            for (u in b) {
                val d = abs(t - u)
                if (d < best) best = d
            }
            acc += best
        }
        return acc / a.size
    }
}
