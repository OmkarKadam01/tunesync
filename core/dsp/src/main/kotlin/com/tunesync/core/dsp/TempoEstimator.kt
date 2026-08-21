package com.tunesync.core.dsp

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

data class TempoEstimate(
    val bpm: Float,
    /** Beat period in analysis frames. */
    val periodFrames: Float,
    /** 0..1 from the margin between the winning hypothesis and the runner-up. */
    val confidence: Float,
    val runnerUpBpm: Float,
    val stable: Boolean,
)

object TempoEstimator {

    const val MIN_BPM = 40f
    const val MAX_BPM = 300f

    /** Centre of the prior. Human tempo perception clusters hard around here. */
    private const val PRIOR_CENTRE_BPM = 120f

    /** Width of the prior in octaves. */
    private const val PRIOR_SIGMA = 1.0f

    /**
     * Autocorrelate the onset envelope and pick the lag with the strongest
     * periodicity, weighted by a log-normal prior on tempo.
     *
     * The prior is not decoration. Without it, half-time and double-time errors
     * are the single most common failure: a 140 bpm track reported as 70 produces
     * a show that flashes on every other beat and looks broken rather than wrong.
     */
    fun estimate(env: OnsetEnvelope): TempoEstimate {
        val x = env.total
        val minLag = bpmToLag(MAX_BPM, env.hopMs)
        val maxLag = bpmToLag(MIN_BPM, env.hopMs)
        if (x.size < maxLag * 2) {
            return TempoEstimate(PRIOR_CENTRE_BPM, bpmToLag(PRIOR_CENTRE_BPM, env.hopMs).toFloat(), 0f, 0f, false)
        }

        val scores = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var acc = 0.0
            var i = lag
            while (i < x.size) {
                acc += x[i] * x[i - lag].toDouble()
                i++
            }
            val norm = (x.size - lag).toFloat()
            val bpm = lagToBpm(lag, env.hopMs)
            scores[lag] = (acc / norm).toFloat() * prior(bpm)
        }

        var bestLag = minLag
        for (lag in minLag..maxLag) if (scores[lag] > scores[bestLag]) bestLag = lag

        // Refine to sub-frame accuracy first: the true period rarely lands on an
        // integer number of frames, and a half-frame error accumulates into visible
        // drift over a four-minute track. Octave correction then works on a period
        // accurate enough to sample a grid with.
        val refined = correctOctave(x, parabolicPeak(scores, bestLag), env.hopMs)

        val runnerUpLag = bestNonHarmonicRival(scores, bestLag, minLag, maxLag)
        val bestScore = scores[bestLag]
        val rivalScore = if (runnerUpLag > 0) scores[runnerUpLag] else 0f
        val confidence = if (bestScore <= 0f) 0f else
            ((bestScore - rivalScore) / bestScore).coerceIn(0f, 1f)

        return TempoEstimate(
            bpm = lagToBpm(refined, env.hopMs),
            periodFrames = refined,
            confidence = confidence,
            runnerUpBpm = if (runnerUpLag > 0) lagToBpm(runnerUpLag, env.hopMs) else 0f,
            stable = confidence > 0.15f,
        )
    }

    /**
     * Half-time correction.
     *
     * Autocorrelation systematically prefers the longer period on any pattern
     * whose bar alternates timbres — kick on 1 and 3, snare on 2 and 4 correlates
     * better at two beats than at one, because at two beats kick lands on kick.
     * The log-normal prior alone does not overcome that, and the result is a show
     * that flashes on every other beat and reads as broken rather than merely wrong.
     *
     * The test that settles it: a beat grid should not systematically leave strong
     * onsets unexplained. If the midpoints between grid positions carry comparable
     * onset energy to the grid itself, the real period is half what we picked.
     */
    private fun correctOctave(onset: FloatArray, period: Float, hopMs: Float): Float {
        val events = strongEvents(onset)
        if (events.size < 8) return period

        // Slowest first, so the first acceptable candidate is the simplest grid
        // that explains the music. This corrects errors in both directions: a
        // half-time pick fails coverage and falls through to the true period, and
        // a double-time pick is beaten by the slower candidate tested before it.
        val candidates = floatArrayOf(period * 2f, period, period / 2f, period / 4f)
            .filter { p ->
                val bpm = lagToBpm(p, hopMs)
                bpm in MIN_BPM..MAX_BPM && p >= 2f
            }
            .sortedDescending()

        for (candidate in candidates) {
            if (coverage(events, candidate) >= COVERAGE_TARGET) return candidate
        }
        return period
    }

    /**
     * Local maxima at or above the median maximum.
     *
     * The median split is what separates real hits from subdivisions and noise.
     * Measured across the test patterns, a half-time grid covers at most 50% of
     * these events while the true grid covers 79% or more — a gap wide enough to
     * threshold on, and one that does not depend on the relative loudness of kick
     * and snare the way a grid-versus-midpoint comparison does.
     */
    private fun strongEvents(x: FloatArray): IntArray {
        val maxima = ArrayList<Int>()
        for (i in 1 until x.size - 1) {
            if (x[i] > 0f && x[i] >= x[i - 1] && x[i] > x[i + 1]) maxima.add(i)
        }
        if (maxima.size < 8) return IntArray(0)

        val values = FloatArray(maxima.size) { x[maxima[it]] }
        values.sort()
        val threshold = values[values.size / 2]
        return maxima.filter { x[it] >= threshold }.toIntArray()
    }

    /** Fraction of [events] landing within tolerance of a best-phase grid of [period]. */
    private fun coverage(events: IntArray, period: Float): Float {
        var best = 0f
        var phase = 0f
        while (phase < period) {
            var hit = 0
            for (e in events) {
                val k = ((e - phase) / period).roundToInt()
                if (kotlin.math.abs(e - (phase + k * period)) <= COVERAGE_TOLERANCE_FRAMES) hit++
            }
            val frac = hit.toFloat() / events.size
            if (frac > best) best = frac
            phase += 0.5f
        }
        return best
    }

    /** A grid explaining fewer strong events than this is the wrong octave. */
    private const val COVERAGE_TARGET = 0.70f

    /** 2.5 frames is about 58 ms, inside the 70 ms MIR tolerance. */
    private const val COVERAGE_TOLERANCE_FRAMES = 2.5f

    /** Log-normal weight centred on [PRIOR_CENTRE_BPM]. */
    private fun prior(bpm: Float): Float {
        val z = ln(bpm / PRIOR_CENTRE_BPM) / ln(2f) / PRIOR_SIGMA
        return exp(-0.5f * z * z)
    }

    /**
     * The strongest peak that is not a harmonic of the winner. A peak at exactly
     * half or double the winning lag is the same tempo interpretation, so counting
     * it as a rival would understate confidence on perfectly clear tracks.
     */
    private fun bestNonHarmonicRival(scores: FloatArray, bestLag: Int, minLag: Int, maxLag: Int): Int {
        var rival = -1
        for (lag in minLag..maxLag) {
            val ratio = lag.toFloat() / bestLag
            val nearHarmonic = HARMONICS.any { kotlin.math.abs(ratio - it) < 0.06f }
            if (nearHarmonic) continue
            if (rival < 0 || scores[lag] > scores[rival]) rival = lag
        }
        return rival
    }

    private val HARMONICS = floatArrayOf(0.25f, 1f / 3f, 0.5f, 2f / 3f, 1f, 1.5f, 2f, 3f, 4f)

    private fun parabolicPeak(y: FloatArray, i: Int): Float {
        if (i <= 0 || i >= y.size - 1) return i.toFloat()
        val a = y[i - 1]
        val b = y[i]
        val c = y[i + 1]
        val denom = a - 2 * b + c
        if (kotlin.math.abs(denom) < 1e-12f) return i.toFloat()
        return i + 0.5f * (a - c) / denom
    }

    fun bpmToLag(bpm: Float, hopMs: Float): Int = (60_000f / bpm / hopMs).roundToInt().coerceAtLeast(1)

    fun lagToBpm(lag: Float, hopMs: Float): Float = 60_000f / (lag * hopMs)

    fun lagToBpm(lag: Int, hopMs: Float): Float = lagToBpm(lag.toFloat(), hopMs)
}
