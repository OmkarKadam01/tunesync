package com.tunesync.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Mono float PCM at a known rate. Analysis works at [ANALYSIS_RATE]; playback
 * always uses the untouched source file.
 */
class AudioSignal(val samples: FloatArray, val sampleRate: Int) {

    val durationMs: Long get() = samples.size * 1000L / sampleRate

    /** Peak absolute amplitude, for the near-silence guard at import. */
    fun peak(): Float {
        var p = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > p) p = a
        }
        return p
    }

    /**
     * Resample with an anti-alias prefilter. Onsets are broadband so a little
     * aliasing is survivable, but aliased high-frequency energy folds down into
     * the bands the onset detector reads and manufactures spurious transients.
     */
    fun resampleTo(target: Int): AudioSignal {
        if (target == sampleRate) return this
        val src = if (target < sampleRate) {
            lowPass(samples, sampleRate, target * 0.45f)
        } else {
            samples
        }
        val ratio = sampleRate.toDouble() / target
        val outLen = (samples.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = min(i0 + 1, src.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = src[i0] * (1f - frac) + src[i1] * frac
        }
        return AudioSignal(out, target)
    }

    companion object {
        /** Beat tracking gains nothing above ~11 kHz, and halving the rate halves every FFT. */
        const val ANALYSIS_RATE = 22_050

        /** Downmix interleaved 16-bit PCM to mono float in -1..1. */
        fun fromInterleaved(pcm: ShortArray, channels: Int, sampleRate: Int, length: Int = pcm.size): AudioSignal {
            require(channels >= 1) { "channels must be positive" }
            val frames = length / channels
            val out = FloatArray(frames)
            var read = 0
            for (f in 0 until frames) {
                var acc = 0f
                for (c in 0 until channels) acc += pcm[read++] / 32768f
                out[f] = acc / channels
            }
            return AudioSignal(out, sampleRate)
        }

        /** Windowed-sinc FIR, odd length, applied with zero padding at the edges. */
        private fun lowPass(x: FloatArray, rate: Int, cutoffHz: Float): FloatArray {
            val fc = (cutoffHz / rate).coerceIn(0.01f, 0.49f)
            val taps = 31
            val half = taps / 2
            val h = FloatArray(taps)
            var sum = 0f
            for (i in 0 until taps) {
                val n = i - half
                val sinc = if (n == 0) 2f * fc else (sin(2.0 * PI * fc * n) / (PI * n)).toFloat()
                // Hamming window keeps the stopband clean enough for this purpose.
                val w = 0.54f - 0.46f * cos(2.0 * PI * i / (taps - 1)).toFloat()
                h[i] = sinc * w
                sum += h[i]
            }
            for (i in 0 until taps) h[i] /= sum

            val out = FloatArray(x.size)
            for (i in x.indices) {
                var acc = 0f
                val lo = max(0, i - half)
                val hi = min(x.size - 1, i + half)
                for (j in lo..hi) acc += x[j] * h[j - i + half]
                out[i] = acc
            }
            return out
        }
    }
}
