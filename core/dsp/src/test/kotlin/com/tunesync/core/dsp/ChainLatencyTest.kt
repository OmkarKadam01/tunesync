package com.tunesync.core.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The analysis chain has a fixed group delay set by the window shape, the hop
 * and the flux definition. Measuring it with an unambiguous impulse keeps the
 * correction honest: a fudge factor fitted to drum fixtures would encode a
 * property of those fixtures instead.
 */
class ChainLatencyTest {

    private fun impulseAt(ms: Int, rate: Int = SyntheticAudio.RATE): AudioSignal {
        val n = rate * 4
        val x = FloatArray(n)
        val at = ms * rate / 1000
        // A few samples of broadband click, not a single sample, so it survives
        // the resample to the analysis rate.
        for (i in 0 until 8) if (at + i < n) x[at + i] = if (i % 2 == 0) 0.9f else -0.9f
        return AudioSignal(x, rate)
    }

    private fun reportedOnsetMs(trueMs: Int): Float {
        val sig = impulseAt(trueMs).resampleTo(AudioSignal.ANALYSIS_RATE)
        val env = OnsetDetector.detect(Hpss.percussive(Stft.compute(sig)))
        var best = 0
        for (i in env.total.indices) if (env.total[i] > env.total[best]) best = i
        return env.frameTimeMs(best)
    }

    @Test
    fun `chain group delay is corrected to within a few milliseconds`() {
        val errors = intArrayOf(500, 1000, 1500, 2000, 2500).map { truth ->
            val reported = reportedOnsetMs(truth)
            println("impulse at $truth ms reported at ${"%.1f".format(reported)} ms (error ${"%.1f".format(reported - truth)})")
            reported - truth
        }
        val mean = errors.average()
        println("mean chain latency error: ${"%.2f".format(mean)} ms")
        assertTrue(
            abs(mean) < 8.0,
            "chain group delay is $mean ms; adjust OnsetDetector.frameTimeMs so onsets land on the transient",
        )
        assertTrue(
            errors.all { abs(it) < 15f },
            "group delay must be constant across positions, got $errors",
        )
    }
}
