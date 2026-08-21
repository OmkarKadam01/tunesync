package com.tunesync.core.dsp

data class MeterEstimate(
    val beatsPerBar: Int,
    /** Which beat index carries the downbeat: 0 means the first tracked beat does. */
    val phase: Int,
    val confidence: Float,
) {
    val usable: Boolean get() = confidence >= 0.6f
}

/**
 * Bar-level structure from low-band periodicity.
 *
 * The kick pattern carries the bar far more reliably than anything else in a
 * mix, so this scores candidate (metre, phase) pairs by the low-band onset
 * strength landing on their downbeats.
 */
object DownbeatDetector {

    private val CANDIDATE_METRES = intArrayOf(4, 3)

    fun detect(env: OnsetEnvelope, beats: IntArray): MeterEstimate {
        if (beats.size < 8) return MeterEstimate(4, 0, 0f)

        var bestMetre = 4
        var bestPhase = 0
        var bestScore = Float.NEGATIVE_INFINITY
        var runnerUp = Float.NEGATIVE_INFINITY

        for (metre in CANDIDATE_METRES) {
            for (phase in 0 until metre) {
                var onScore = 0f
                var onCount = 0
                var offScore = 0f
                var offCount = 0
                for (i in beats.indices) {
                    val f = beats[i]
                    // Weight the kick band heavily but not exclusively: some genres
                    // put the bar marker on a crash or a chord stab, not a kick.
                    val v = env.low[f] * 0.75f + env.total[f] * 0.25f
                    if ((i - phase).mod(metre) == 0) {
                        onScore += v; onCount++
                    } else {
                        offScore += v; offCount++
                    }
                }
                if (onCount == 0 || offCount == 0) continue
                // Contrast, not absolute strength: a downbeat is defined by being
                // stronger than its neighbours, not by being loud.
                val score = (onScore / onCount) - (offScore / offCount)
                if (score > bestScore) {
                    runnerUp = bestScore
                    bestScore = score
                    bestMetre = metre
                    bestPhase = phase
                } else if (score > runnerUp) {
                    runnerUp = score
                }
            }
        }

        val confidence = when {
            bestScore <= 0f -> 0f
            runnerUp <= 0f -> (bestScore * 2f).coerceIn(0f, 1f)
            else -> ((bestScore - runnerUp) / bestScore).coerceIn(0f, 1f)
        }
        return MeterEstimate(bestMetre, bestPhase, confidence)
    }
}
