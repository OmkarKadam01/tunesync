package com.tunesync.core.dsp

import kotlin.math.min

/**
 * Harmonic–percussive source separation by median filtering.
 *
 * This is how "ignore the vocals" is achieved without a stem-separation model.
 * Vocals, guitars, pads and bass hold pitch, so they draw horizontal lines
 * across a spectrogram; drums are broadband transients, so they draw vertical
 * ones. A median along time keeps what is steady (harmonic); a median along
 * frequency keeps what is broadband (percussive). Only the percussive residue
 * reaches the beat tracker — the harmonic half, vocal included, is discarded.
 */
object Hpss {

    /** ~0.49 s at the default hop. Long enough to average over a vocal vibrato cycle. */
    const val TIME_KERNEL = 21

    /** ~560 Hz at 43 Hz bands. Wide enough to step over a harmonic partial. */
    const val FREQ_KERNEL = 13

    /**
     * Returns the percussive component only. A soft (Wiener) mask rather than a
     * binary one: binary masking leaves comb artefacts that the onset detector
     * reads as extra transients.
     */
    fun percussive(s: Spectrogram): Spectrogram {
        val harmonic = medianAlongTime(s, TIME_KERNEL)
        val percussive = medianAlongFrequency(s, FREQ_KERNEL)

        // Reuse the percussive buffer for the masked output.
        val out = percussive.data
        val h = harmonic.data
        val src = s.data
        for (i in src.indices) {
            val p2 = out[i] * out[i]
            val h2 = h[i] * h[i]
            val denom = p2 + h2
            out[i] = if (denom > 1e-12f) src[i] * (p2 / denom) else 0f
        }
        return percussive
    }

    private fun medianAlongTime(s: Spectrogram, k: Int): Spectrogram {
        val out = s.copyStructure()
        val half = k / 2
        val scratch = FloatArray(k)
        for (b in 0 until s.bands) {
            for (f in 0 until s.frames) {
                var n = 0
                var i = f - half
                val end = f + half
                while (i <= end) {
                    // Clamp at the edges rather than zero-padding: zeros would look
                    // like a transient at the very start and end of every track.
                    val fi = i.coerceIn(0, s.frames - 1)
                    scratch[n++] = s[fi, b]
                    i++
                }
                out[f, b] = median(scratch, n)
            }
        }
        return out
    }

    private fun medianAlongFrequency(s: Spectrogram, k: Int): Spectrogram {
        val out = s.copyStructure()
        val half = k / 2
        val scratch = FloatArray(k)
        for (f in 0 until s.frames) {
            val base = f * s.bands
            for (b in 0 until s.bands) {
                var n = 0
                var i = b - half
                val end = b + half
                while (i <= end) {
                    val bi = i.coerceIn(0, s.bands - 1)
                    scratch[n++] = s.data[base + bi]
                    i++
                }
                out.data[base + b] = median(scratch, n)
            }
        }
        return out
    }

    /**
     * Quickselect median over the first [n] entries, destroying their order.
     * Called tens of millions of times per track, so it allocates nothing and
     * avoids a full sort.
     */
    private fun median(a: FloatArray, n: Int): Float {
        val target = n / 2
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val pivot = a[(lo + hi) ushr 1]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    val t = a[i]; a[i] = a[j]; a[j] = t
                    i++; j--
                }
            }
            if (target <= j) hi = j else if (target >= i) lo = i else break
        }
        return a[min(target, n - 1)]
    }
}
