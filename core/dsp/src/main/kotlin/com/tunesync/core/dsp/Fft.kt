package com.tunesync.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 Cooley–Tukey FFT on primitive arrays.
 *
 * Twiddle factors and the bit-reversal permutation are precomputed once per
 * size, because the STFT calls this thousands of times per track and allocating
 * per call is most of the cost.
 */
class Fft(val size: Int) {

    init {
        require(size > 1 && size and (size - 1) == 0) { "FFT size must be a power of two, got $size" }
    }

    private val levels = Integer.numberOfTrailingZeros(size)
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
    private val reverse = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }

    /** Transforms [re]/[im] in place. Both must be [size] long. */
    fun transform(re: FloatArray, im: FloatArray) {
        require(re.size == size && im.size == size) { "buffers must be $size long" }

        for (i in 0 until size) {
            val j = reverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var span = 2
        while (span <= size) {
            val half = span / 2
            val step = size / span
            var i = 0
            while (i < size) {
                var j = i
                var k = 0
                while (j < i + half) {
                    val l = j + half
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    j++
                    k += step
                }
                i += span
            }
            span = span shl 1
        }
    }
}
