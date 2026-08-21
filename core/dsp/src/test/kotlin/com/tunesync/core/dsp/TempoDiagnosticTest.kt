package com.tunesync.core.dsp

import kotlin.test.Test

/**
 * Not an assertion suite — a printout used to tune the octave-correction
 * threshold against real envelope statistics rather than by guessing.
 */
class TempoDiagnosticTest {

    @Test
    fun `print peak coverage at each octave`() {
        for ((bpm, vocal) in listOf(90f to false, 120f to false, 120f to true, 140f to true)) {
            val track = SyntheticAudio.drumPattern(bpm, bars = 16, withVocal = vocal)
            val sig = track.signal.resampleTo(AudioSignal.ANALYSIS_RATE)
            val raw = Stft.compute(sig)
            val env = OnsetDetector.detect(Hpss.percussive(raw))
            val envNoHpss = OnsetDetector.detect(raw)
            if (vocal) {
                println("--- vocal present: energy retained by HPSS = %.3f"
                    .format(env.total.sum() / envNoHpss.total.sum()))
            }

            val maxima = localMaxima(env.total)
            val sorted = maxima.map { env.total[it] }.sorted()
            fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
            println(
                "=== $bpm bpm vocal=$vocal | %d maxima | p25 %.2f p50 %.2f p75 %.2f p90 %.2f p95 %.2f"
                    .format(maxima.size, pct(.25), pct(.5), pct(.75), pct(.90), pct(.95)),
            )

            for (frac in doubleArrayOf(0.25, 0.5, 0.75)) {
                val thresh = pct(frac)
                val strong = maxima.filter { env.total[it] >= thresh }.toIntArray()
                val line = StringBuilder("  keep>=p%02.0f (%3d events): ".format(frac * 100, strong.size))
                for (mult in floatArrayOf(0.5f, 1f, 2f)) {
                    val period = 60_000f / (bpm * mult) / env.hopMs
                    line.append("%5.1fbpm=%.2f  ".format(bpm * mult, coverage(strong, period)))
                }
                println(line)
            }
        }
    }

    private fun localMaxima(x: FloatArray): List<Int> =
        (1 until x.size - 1).filter { x[it] > 0f && x[it] >= x[it - 1] && x[it] > x[it + 1] }

    /** Fraction of [events] within tolerance of a best-phase grid of [period]. */
    private fun coverage(events: IntArray, period: Float): Float {
        if (events.isEmpty()) return 0f
        val tol = 2.5f
        var best = 0f
        var phase = 0f
        while (phase < period) {
            var hit = 0
            for (e in events) {
                val k = ((e - phase) / period).toDouble().let { Math.round(it) }
                val grid = phase + k * period
                if (kotlin.math.abs(e - grid) <= tol) hit++
            }
            val frac = hit.toFloat() / events.size
            if (frac > best) best = frac
            phase += 0.5f
        }
        return best
    }

}
