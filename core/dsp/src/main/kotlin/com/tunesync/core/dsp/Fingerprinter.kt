package com.tunesync.core.dsp

import kotlin.math.abs
import kotlin.math.ln

/**
 * Landmark fingerprinting for **alignment**, not identification.
 *
 * We already know which song is playing — the user imported it. The only
 * unknown is *where* in it we are. That is a far easier problem than Shazam
 * solves: the index holds one track, so hashes only have to be distinctive
 * within it rather than across millions of recordings.
 *
 * Peak pairs survive the things that destroy a raw spectrogram in a room —
 * reverb, crowd noise, a phone's own microphone response — because a spectral
 * peak stays a peak when everything around it gets louder.
 */
object Fingerprinter {

    /** Peaks kept per second. Denser is more robust and more expensive. */
    const val PEAKS_PER_SECOND = 32

    /** How many later peaks each peak pairs with. */
    const val FAN_OUT = 6

    /** Pair separation in frames: ~23 ms to ~1.5 s at the default hop. */
    const val MIN_DT = 1
    const val MAX_DT = 63

    /** Pairs wider apart in frequency than this are not worth encoding. */
    const val MAX_BAND_SPAN = 48

    /** Half-size of the neighbourhood a peak must dominate, in frames and bands. */
    private const val NEIGHBOURHOOD = 2

    /**
     * Packed `(hash << 32) | frame`, sorted ascending so a hash lookup is a
     * binary search over a primitive array with no boxing and no hash map.
     */
    fun hashes(spec: Spectrogram): LongArray {
        val peaks = peaks(spec)
        if (peaks.size < 2) return LongArray(0)

        val out = LongArray(peaks.size * FAN_OUT)
        var n = 0
        for (i in peaks.indices) {
            val t1 = peakFrame(peaks[i])
            val f1 = peakBand(peaks[i])
            var paired = 0
            var j = i + 1
            while (j < peaks.size && paired < FAN_OUT) {
                val t2 = peakFrame(peaks[j])
                val dt = t2 - t1
                if (dt < MIN_DT) { j++; continue }
                if (dt > MAX_DT) break
                val f2 = peakBand(peaks[j])
                if (abs(f2 - f1) > MAX_BAND_SPAN) { j++; continue }

                // Bands are 0..255, so each fits a byte exactly; dt is capped at 63.
                val hash = (f1 shl 16) or (f2 shl 8) or dt
                out[n++] = (hash.toLong() shl 32) or (t1.toLong() and 0xFFFFFFFFL)
                paired++
                j++
            }
        }
        val trimmed = out.copyOf(n)
        trimmed.sort()
        return trimmed
    }

    /**
     * Spectral peaks as packed `(frame << 16) | band`, ascending by frame.
     *
     * A peak must dominate its time-frequency neighbourhood, and the set is then
     * thinned to a fixed rate per second. The density cap is what keeps a loud
     * passage from contributing ten times the hashes of a quiet one, which would
     * otherwise bias every match toward the chorus.
     */
    fun peaks(spec: Spectrogram): IntArray {
        val frames = spec.frames
        val bands = spec.bands
        if (frames < 3 || bands < 3) return IntArray(0)

        val log = FloatArray(spec.data.size)
        for (i in log.indices) log[i] = ln(1f + spec.data[i])

        val candidates = ArrayList<Long>(frames * 4)
        for (t in 0 until frames) {
            for (b in 0 until bands) {
                val v = log[t * bands + b]
                if (v <= 0f) continue
                if (!dominatesNeighbourhood(log, frames, bands, t, b, v)) continue
                // Magnitude in the high bits so a descending sort ranks by strength.
                candidates.add((v.toRawBits().toLong() shl 32) or ((t shl 16) or b).toLong())
            }
        }
        if (candidates.isEmpty()) return IntArray(0)

        val framesPerSecond = 1000f / spec.hopMs
        val bucketFrames = framesPerSecond.toInt().coerceAtLeast(1)
        val perBucket = HashMap<Int, ArrayList<Long>>()
        for (c in candidates) {
            val packed = (c and 0xFFFFFFFFL).toInt()
            val bucket = (packed shr 16) / bucketFrames
            perBucket.getOrPut(bucket) { ArrayList() }.add(c)
        }

        val kept = ArrayList<Int>(candidates.size)
        for (bucket in perBucket.values) {
            bucket.sortDescending()
            for (k in 0 until minOf(PEAKS_PER_SECOND, bucket.size)) {
                kept.add((bucket[k] and 0xFFFFFFFFL).toInt())
            }
        }
        val out = kept.toIntArray()
        out.sort()
        return out
    }

    private fun dominatesNeighbourhood(
        log: FloatArray,
        frames: Int,
        bands: Int,
        t: Int,
        b: Int,
        v: Float,
    ): Boolean {
        val t0 = (t - NEIGHBOURHOOD).coerceAtLeast(0)
        val t1 = (t + NEIGHBOURHOOD).coerceAtMost(frames - 1)
        val b0 = (b - NEIGHBOURHOOD).coerceAtLeast(0)
        val b1 = (b + NEIGHBOURHOOD).coerceAtMost(bands - 1)
        for (tt in t0..t1) {
            val base = tt * bands
            for (bb in b0..b1) {
                if (tt == t && bb == b) continue
                if (log[base + bb] > v) return false
            }
        }
        return true
    }

    fun peakFrame(packed: Int): Int = packed shr 16

    fun peakBand(packed: Int): Int = packed and 0xFFFF

    fun hashOf(entry: Long): Int = (entry ushr 32).toInt()

    fun frameOf(entry: Long): Int = (entry and 0xFFFFFFFFL).toInt()
}
