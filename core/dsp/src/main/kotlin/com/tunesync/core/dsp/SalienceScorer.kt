package com.tunesync.core.dsp

/**
 * Per-beat weight in 0..1 deciding which beats become cues — the "highest nodes"
 * the show fires on.
 *
 * Show generation thresholds on this rather than firing on every beat, which is
 * both the aesthetic win and, at high tempo, half of how output stays under the
 * flash ceiling.
 */
object SalienceScorer {

    private const val W_LOW = 0.45f
    private const val W_TOTAL = 0.25f
    private const val W_METRIC = 0.20f
    private const val W_LOUDNESS = 0.10f

    class Scored(
        val salience: FloatArray,
        val low: FloatArray,
        val mid: FloatArray,
        val high: FloatArray,
    )

    fun score(
        env: OnsetEnvelope,
        spectrogram: Spectrogram,
        beats: IntArray,
        meter: MeterEstimate,
    ): Scored {
        val n = beats.size
        val lowAt = FloatArray(n) { env.low[beats[it]] }
        val midAt = FloatArray(n) { env.mid[beats[it]] }
        val highAt = FloatArray(n) { env.high[beats[it]] }
        val totalAt = FloatArray(n) { env.total[beats[it]] }
        val loudness = localLoudness(spectrogram, beats, env.hopMs, meter.beatsPerBar)

        // Normalise against the track's own distribution, not an absolute scale:
        // a quiet acoustic track should still produce a full range of salience.
        normaliseToPercentile(lowAt)
        normaliseToPercentile(midAt)
        normaliseToPercentile(highAt)
        normaliseToPercentile(totalAt)
        normaliseToPercentile(loudness)

        val salience = FloatArray(n)
        for (i in 0 until n) {
            val pos = if (meter.usable) (i - meter.phase).mod(meter.beatsPerBar) + 1 else 0
            salience[i] = (
                W_LOW * lowAt[i] +
                    W_TOTAL * totalAt[i] +
                    W_METRIC * metricWeight(pos, meter.beatsPerBar) +
                    W_LOUDNESS * loudness[i]
                ).coerceIn(0f, 1f)
        }
        return Scored(salience, lowAt, midAt, highAt)
    }

    /**
     * Metrical strength by position in the bar. The backbeat scores well above a
     * passing beat because that is where the snare lands in most popular music,
     * and it is what a crowd claps on.
     */
    private fun metricWeight(pos: Int, beatsPerBar: Int): Float = when {
        pos == 0 -> 0.3f              // metre unknown; stay neutral
        pos == 1 -> 1.0f              // downbeat
        beatsPerBar == 4 && pos % 2 == 0 -> 0.6f  // backbeat: 2 and 4
        beatsPerBar == 4 && pos == 3 -> 0.45f
        else -> 0.3f
    }

    /** Broadband energy averaged over a two-bar window around each beat. */
    private fun localLoudness(
        s: Spectrogram,
        beats: IntArray,
        hopMs: Float,
        beatsPerBar: Int,
    ): FloatArray {
        val frameEnergy = FloatArray(s.frames)
        for (f in 0 until s.frames) {
            var acc = 0f
            val base = f * s.bands
            for (b in 0 until s.bands) acc += s.data[base + b]
            frameEnergy[f] = acc
        }
        val prefix = DoubleArray(s.frames + 1)
        for (i in 0 until s.frames) prefix[i + 1] = prefix[i] + frameEnergy[i]

        val out = FloatArray(beats.size)
        val halfWindow = beats.size.let {
            if (it < 2) 43 else {
                val meanPeriod = (beats.last() - beats.first()).toFloat() / (it - 1)
                (meanPeriod * beatsPerBar).toInt().coerceAtLeast(1)
            }
        }
        for (i in beats.indices) {
            val lo = (beats[i] - halfWindow).coerceAtLeast(0)
            val hi = (beats[i] + halfWindow).coerceAtMost(s.frames)
            out[i] = ((prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()
        }
        return out
    }

    /**
     * Scale so the 95th percentile maps to 1.0. Dividing by the maximum instead
     * would let one cymbal crash flatten every other beat to near zero.
     */
    private fun normaliseToPercentile(x: FloatArray, percentile: Float = 0.95f) {
        if (x.isEmpty()) return
        val sorted = x.copyOf()
        sorted.sort()
        val idx = ((sorted.size - 1) * percentile).toInt()
        val scale = sorted[idx]
        if (scale <= 1e-9f) return
        for (i in x.indices) x[i] = (x[i] / scale).coerceIn(0f, 1f)
    }
}
