package com.tunesync.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Ellis dynamic-programming beat tracker.
 *
 * Finds the beat sequence maximising cumulative onset strength subject to a
 * penalty for deviating from the estimated period. Because the optimum is
 * global over the whole track, a weak bridge or a dropout does not derail the
 * grid the way a greedy peak-picker would.
 */
object BeatTracker {

    /** How hard deviation from the estimated period is punished. */
    private const val TIGHTNESS = 100f

    fun track(env: OnsetEnvelope, tempo: TempoEstimate): IntArray {
        val period = tempo.periodFrames
        if (period < 2f || env.frames < 4) return IntArray(0)

        val local = localScore(env.total, period)
        val n = local.size

        val minLag = (period * 0.5f).roundToInt().coerceAtLeast(1)
        val maxLag = (period * 2.0f).roundToInt().coerceAtLeast(minLag + 1)

        // Precompute the transition penalty for each candidate lag: a parabola in
        // log-period space, so being 10% fast costs the same as being 10% slow.
        val penalty = FloatArray(maxLag - minLag + 1)
        for (i in penalty.indices) {
            val lag = (minLag + i).toFloat()
            val d = ln(lag / period)
            penalty[i] = -TIGHTNESS * d * d
        }

        val cumulative = FloatArray(n)
        val backlink = IntArray(n) { -1 }

        for (t in 0 until n) {
            var best = Float.NEGATIVE_INFINITY
            var bestTau = -1
            for (i in penalty.indices) {
                val tau = t - (minLag + i)
                if (tau < 0) continue
                val score = penalty[i] + cumulative[tau]
                if (score > best) {
                    best = score
                    bestTau = tau
                }
            }
            if (bestTau < 0) {
                cumulative[t] = local[t]
            } else {
                cumulative[t] = local[t] + best
                backlink[t] = bestTau
            }
        }

        // Start the backtrace from the last strong peak rather than the global
        // maximum: cumulative score grows monotonically, so the global max is
        // always near the end and may be a fade-out artefact.
        val start = finalBeatCandidate(cumulative)
        if (start < 0) return IntArray(0)

        val reversed = ArrayList<Int>(n / minLag + 2)
        var t = start
        while (t >= 0) {
            reversed.add(t)
            t = backlink[t]
        }
        reversed.reverse()

        return trimEdges(reversed.toIntArray(), local)
    }

    /**
     * Smooth the onset envelope with a half-period Hann window and standardise it.
     * Smoothing keeps a single hit from being split across two frames; the DP
     * needs a score that is comparable across the whole track.
     */
    private fun localScore(onset: FloatArray, period: Float): FloatArray {
        val w = (period / 2f).roundToInt().coerceAtLeast(1)
        val win = FloatArray(2 * w + 1) { 0.5f - 0.5f * cos(2.0 * PI * it / (2 * w)).toFloat() }
        var wsum = 0f
        for (v in win) wsum += v
        if (wsum > 0f) for (i in win.indices) win[i] /= wsum

        val n = onset.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            for (j in win.indices) {
                val k = i + j - w
                if (k in 0 until n) acc += onset[k] * win[j]
            }
            out[i] = acc
        }

        var mean = 0f
        for (v in out) mean += v
        mean /= n
        var varSum = 0f
        for (v in out) {
            val d = v - mean
            varSum += d * d
        }
        val sd = sqrt(varSum / n)
        if (sd > 1e-9f) for (i in out.indices) out[i] = (out[i] - mean) / sd
        return out
    }

    /** Last frame whose cumulative score is within a hair of the local maximum. */
    private fun finalBeatCandidate(cumulative: FloatArray): Int {
        if (cumulative.isEmpty()) return -1
        var max = Float.NEGATIVE_INFINITY
        for (v in cumulative) if (v > max) max = v
        val threshold = max - kotlin.math.abs(max) * 0.01f
        for (i in cumulative.indices.reversed()) {
            if (cumulative[i] >= threshold) return i
        }
        return cumulative.size - 1
    }

    /**
     * Drop leading and trailing beats that sit on no actual onset — the DP will
     * happily extrapolate a grid across a silent intro or a fade-out.
     */
    private fun trimEdges(beats: IntArray, local: FloatArray): IntArray {
        if (beats.size < 3) return beats
        var lo = 0
        var hi = beats.size - 1
        while (lo < hi && local[beats[lo]] < TRIM_THRESHOLD) lo++
        while (hi > lo && local[beats[hi]] < TRIM_THRESHOLD) hi--
        return beats.copyOfRange(lo, hi + 1)
    }

    /** In standardised units, so this is "a fifth of a standard deviation below the mean". */
    private const val TRIM_THRESHOLD = -0.2f
}
