package com.tunesync.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Magnitude spectrogram stored as one flat array, frame-major.
 *
 * Flat rather than Array<FloatArray> because a four-minute track is ~10,000
 * frames and the per-object overhead plus pointer chasing is measurable in the
 * median filters that HPSS runs over this.
 */
class Spectrogram(
    val frames: Int,
    val bands: Int,
    val hopMs: Float,
    val bandWidthHz: Float,
    val data: FloatArray = FloatArray(frames * bands),
) {
    operator fun get(frame: Int, band: Int): Float = data[frame * bands + band]

    operator fun set(frame: Int, band: Int, v: Float) {
        data[frame * bands + band] = v
    }

    fun frameTimeMs(frame: Int): Float = frame * hopMs

    fun bandForHz(hz: Float): Int = (hz / bandWidthHz).toInt().coerceIn(0, bands - 1)

    fun copyStructure() = Spectrogram(frames, bands, hopMs, bandWidthHz)
}

/**
 * Short-time Fourier transform, band-reduced on the way out.
 *
 * The full 2048-point transform gives 1025 bins at 10.8 Hz each. Beat tracking
 * needs time resolution, not that much frequency resolution, so bins are pooled
 * into [BANDS] linear bands of ~43 Hz. That cuts the memory and the cost of the
 * HPSS median filters fourfold while still resolving a harmonic partial as a
 * spike, which is what the vertical median needs in order to reject it.
 */
object Stft {

    const val N_FFT = 2048
    const val HOP = 512
    const val BANDS = 256

    fun compute(signal: AudioSignal): Spectrogram {
        val n = N_FFT
        val fft = Fft(n)
        val window = FloatArray(n) { 0.5f - 0.5f * cos(2.0 * PI * it / n).toFloat() }

        val frames = maxOf(1, 1 + signal.samples.size / HOP)
        val binsPerBand = (n / 2) / BANDS
        val bandWidthHz = signal.sampleRate.toFloat() / n * binsPerBand
        val hopMs = HOP * 1000f / signal.sampleRate
        val out = Spectrogram(frames, BANDS, hopMs, bandWidthHz)

        val re = FloatArray(n)
        val im = FloatArray(n)
        val x = signal.samples
        val half = n / 2

        for (f in 0 until frames) {
            // Centred frames: frame f is centred on sample f*HOP, so its timestamp
            // is f*hopMs with no correction. Timestamping from the window *start*
            // instead reports every onset about half a window early — 46 ms at this
            // size, which is most of the 70 ms accuracy budget spent on nothing.
            val start = f * HOP - half
            for (i in 0 until n) {
                val src = start + i
                // Reflect at the edges. Zero padding would look like a transient at
                // the first and last frame of every track.
                val s = when {
                    src < 0 -> -src
                    src >= x.size -> 2 * x.size - 2 - src
                    else -> src
                }
                re[i] = if (s in x.indices) x[s] * window[i] else 0f
            }
            java.util.Arrays.fill(im, 0f)

            fft.transform(re, im)

            // Pool magnitudes into bands. Sum of energy, then root, so a broadband
            // transient accumulates across the band instead of being averaged away.
            var bin = 0
            for (b in 0 until BANDS) {
                var energy = 0f
                for (k in 0 until binsPerBand) {
                    val r = re[bin]
                    val i2 = im[bin]
                    energy += r * r + i2 * i2
                    bin++
                }
                out[f, b] = sqrt(energy)
            }
        }
        return out
    }
}
