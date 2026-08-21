package com.tunesync.core.dsp

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Per-band onset strength over time.
 *
 * The bands are kept separate rather than summed away because that is what lets
 * a style say "fire on kicks only" later, and what gives the salience scorer a
 * kick weight distinct from overall transient energy.
 */
class OnsetEnvelope(
    val total: FloatArray,
    val low: FloatArray,
    val mid: FloatArray,
    val high: FloatArray,
    val hopMs: Float,
) {
    val frames: Int get() = total.size

    /**
     * Time of the transient responsible for the flux at [frame].
     *
     * Frames are centred, so frame f sits at f*hop. Flux is a backward difference,
     * which peaks while the magnitude is still *rising* — before the transient
     * reaches the window centre. [GROUP_DELAY_HOPS] compensates; it is measured
     * with isolated impulses in ChainLatencyTest rather than fitted to music, and
     * that test fails if a change to the window, hop or flux definition moves it.
     */
    fun frameTimeMs(frame: Int): Float = (frame + GROUP_DELAY_HOPS) * hopMs

    fun frameAtMs(ms: Float): Int =
        (ms / hopMs - GROUP_DELAY_HOPS).roundToInt().coerceIn(0, frames - 1)

    companion object {
        /** Measured group delay of the STFT + flux chain, in hops. */
        const val GROUP_DELAY_HOPS = 0.60f
    }
}

object OnsetDetector {

    private const val LOW_HZ = 200f
    private const val MID_HZ = 2000f

    /**
     * Half-wave rectified log-magnitude spectral flux.
     *
     * Log domain because a 6 dB jump matters equally in a quiet verse and a loud
     * chorus; rectified because only energy *appearing* is an onset, and the
     * decay after a hit would otherwise register as a second event.
     */
    fun detect(percussive: Spectrogram): OnsetEnvelope {
        val frames = percussive.frames
        val bands = percussive.bands
        val lowEnd = percussive.bandForHz(LOW_HZ)
        val midEnd = percussive.bandForHz(MID_HZ)

        val total = FloatArray(frames)
        val low = FloatArray(frames)
        val mid = FloatArray(frames)
        val high = FloatArray(frames)

        for (f in 1 until frames) {
            var lo = 0f
            var md = 0f
            var hi = 0f
            val cur = f * bands
            val prev = (f - 1) * bands
            for (b in 0 until bands) {
                val d = ln(1f + percussive.data[cur + b]) - ln(1f + percussive.data[prev + b])
                if (d > 0f) {
                    when {
                        b <= lowEnd -> lo += d
                        b <= midEnd -> md += d
                        else -> hi += d
                    }
                }
            }
            low[f] = lo
            mid[f] = md
            high[f] = hi
            total[f] = lo + md + hi
        }

        normalise(total)
        normalise(low)
        normalise(mid)
        normalise(high)
        return OnsetEnvelope(total, low, mid, high, percussive.hopMs)
    }

    /**
     * Subtract a local baseline and scale to unit standard deviation, so a track
     * that gets louder does not simply produce bigger onsets in the second half.
     */
    private fun normalise(x: FloatArray) {
        if (x.isEmpty()) return
        subtractMovingMean(x, window = 43)  // ~1 s

        var mean = 0f
        for (v in x) mean += v
        mean /= x.size
        var varSum = 0f
        for (v in x) {
            val d = v - mean
            varSum += d * d
        }
        val sd = sqrt(varSum / x.size)
        if (sd < 1e-9f) return
        for (i in x.indices) x[i] = max(0f, x[i] / sd)
    }

    private fun subtractMovingMean(x: FloatArray, window: Int) {
        val n = x.size
        if (n <= window) return
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
        val half = window / 2
        for (i in 0 until n) {
            val lo = maxOf(0, i - half)
            val hi = minOf(n, i + half + 1)
            val localMean = ((prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()
            x[i] = max(0f, x[i] - localMean)
        }
    }
}
