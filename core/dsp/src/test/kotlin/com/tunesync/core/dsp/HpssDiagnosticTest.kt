package com.tunesync.core.dsp

import kotlin.test.Test

/** Prints the separation masks so the failure can be located rather than guessed at. */
class HpssDiagnosticTest {

    @Test
    fun `print masks at a vocal partial and a drum transient`() {
        val vocal = SyntheticAudio.vocalOnly(seconds = 10f)
        val spec = Stft.compute(vocal.resampleTo(AudioSignal.ANALYSIS_RATE))

        println("spectrogram: ${spec.frames} frames x ${spec.bands} bands, " +
            "bandWidth ${"%.1f".format(spec.bandWidthHz)} Hz")

        // Where should the 220 Hz fundamental and its harmonics sit?
        for (hz in intArrayOf(220, 440, 660, 880)) {
            println("  ${hz} Hz -> band ${spec.bandForHz(hz.toFloat())}")
        }

        val f = spec.frames / 2
        println("  magnitudes at frame $f, bands 0..30:")
        val sb = StringBuilder("    ")
        for (b in 0..30) sb.append("%d:%.1f ".format(b, spec[f, b]))
        println(sb)

        val h = medianTime(spec, Hpss.TIME_KERNEL)
        val p = medianFreq(spec, Hpss.FREQ_KERNEL)
        println("  band  S       H       P       maskP")
        for (b in intArrayOf(3, 5, 7, 10, 12, 15, 20, 25)) {
            val s = spec[f, b]
            val hv = h[f, b]
            val pv = p[f, b]
            val mask = if (pv * pv + hv * hv > 1e-12f) pv * pv / (pv * pv + hv * hv) else 0f
            println("  %4d  %-7.2f %-7.2f %-7.2f %.4f".format(b, s, hv, pv, mask))
        }
    }

    private fun medianTime(s: Spectrogram, k: Int): Spectrogram {
        val out = s.copyStructure()
        val half = k / 2
        val buf = FloatArray(k)
        for (b in 0 until s.bands) for (f in 0 until s.frames) {
            for (i in 0 until k) buf[i] = s[(f - half + i).coerceIn(0, s.frames - 1), b]
            buf.sort()
            out[f, b] = buf[k / 2]
        }
        return out
    }

    private fun medianFreq(s: Spectrogram, k: Int): Spectrogram {
        val out = s.copyStructure()
        val half = k / 2
        val buf = FloatArray(k)
        for (f in 0 until s.frames) for (b in 0 until s.bands) {
            for (i in 0 until k) buf[i] = s[f, (b - half + i).coerceIn(0, s.bands - 1)]
            buf.sort()
            out[f, b] = buf[k / 2]
        }
        return out
    }
}
