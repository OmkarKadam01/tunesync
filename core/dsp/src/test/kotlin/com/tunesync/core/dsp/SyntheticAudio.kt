package com.tunesync.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Drum-machine-style test signals with known ground truth.
 *
 * Real audio fixtures would be better and are what the 60-track corpus is for,
 * but synthetic patterns pin down the properties that must hold exactly: the
 * tempo is known to the sample, and so is every beat position.
 */
object SyntheticAudio {

    const val RATE = 44_100

    class Track(val signal: AudioSignal, val beatTimesMs: List<Int>, val downbeatTimesMs: List<Int>)

    /**
     * A 4/4 pattern: kick on 1 and 3, snare on 2 and 4, hats on eighths, with an
     * optional sustained vocal-like tone to verify HPSS discards it.
     */
    fun drumPattern(
        bpm: Float,
        bars: Int = 16,
        withVocal: Boolean = false,
        withNoise: Float = 0f,
        swing: Float = 0f,
        seed: Int = 7,
    ): Track {
        val beatMs = 60_000f / bpm
        val totalBeats = bars * 4
        val lengthSamples = ((totalBeats + 1) * beatMs / 1000f * RATE).toInt()
        val x = FloatArray(lengthSamples)
        val rng = Random(seed)

        val beatTimes = ArrayList<Int>(totalBeats)
        val downbeatTimes = ArrayList<Int>(bars)

        for (b in 0 until totalBeats) {
            val tMs = b * beatMs
            beatTimes += tMs.toInt()
            val posInBar = b % 4
            if (posInBar == 0) downbeatTimes += tMs.toInt()

            val at = (tMs / 1000f * RATE).toInt()
            when (posInBar) {
                0, 2 -> kick(x, at, gain = if (posInBar == 0) 1.0f else 0.7f)
                1, 3 -> snare(x, at, rng, gain = 0.8f)
            }
            // Eighth-note hats, optionally swung, so the tracker has to prefer the
            // quarter-note pulse over the denser subdivision. The bar's first beat
            // takes an accent, because without one beats 1 and 3 are literally
            // indistinguishable here and no detector could tell them apart.
            hat(x, at, rng, gain = if (posInBar == 0) 0.5f else 0.2f)
            val offset = (0.5f + swing * 0.16f) * beatMs
            hat(x, ((tMs + offset) / 1000f * RATE).toInt(), rng, gain = 0.16f)
        }

        if (withVocal) {
            // Sustained, pitched, vibrato'd — harmonic content HPSS must reject.
            for (i in x.indices) {
                x[i] += (voiceSample(i.toDouble() / RATE) * 0.22).toFloat()
            }
        }

        if (withNoise > 0f) {
            for (i in x.indices) x[i] += (rng.nextFloat() * 2f - 1f) * withNoise
        }

        var peak = 0f
        for (v in x) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        if (peak > 0f) for (i in x.indices) x[i] = x[i] / peak * 0.9f

        return Track(AudioSignal(x, RATE), beatTimes, downbeatTimes)
    }

    /** The vibrato'd harmonic stack alone, with no percussion to hide behind. */
    fun vocalOnly(seconds: Float = 20f): AudioSignal {
        val n = (seconds * RATE).toInt()
        val x = FloatArray(n)
        for (i in 0 until n) x[i] = (voiceSample(i.toDouble() / RATE) * 0.4).toFloat()
        return AudioSignal(x, RATE)
    }

    private const val VOICE_F0 = 220.0
    private const val VIBRATO_HZ = 5.5
    private const val VIBRATO_DEPTH = 0.02

    /**
     * Four harmonics with vibrato.
     *
     * Vibrato has to modulate the *phase*, not multiply it. Writing
     * `sin(2π·f·(1 + d·sin(2π·fv·t))·t)` looks like frequency modulation but its
     * phase derivative carries a term proportional to t, so the deviation grows
     * without bound and a few seconds in the "voice" is a broadband sweep. That
     * is not a harmonic signal, and no separation algorithm should treat it as one.
     */
    private fun voiceSample(t: Double): Double {
        var v = 0.0
        for (h in 1..4) {
            val f = VOICE_F0 * h
            val modIndex = f * VIBRATO_DEPTH / VIBRATO_HZ
            v += sin(2 * PI * f * t - modIndex * cos(2 * PI * VIBRATO_HZ * t)) / h
        }
        return v
    }

    /** Pitched, sustained, no transients — the analyser should refuse this. */
    fun sustainedTone(seconds: Float = 20f, hz: Double = 330.0): AudioSignal {
        val n = (seconds * RATE).toInt()
        val x = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            x[i] = (0.5 * sin(2 * PI * hz * t) + 0.2 * sin(2 * PI * hz * 2 * t)).toFloat()
        }
        return AudioSignal(x, RATE)
    }

    fun silence(seconds: Float = 5f): AudioSignal =
        AudioSignal(FloatArray((seconds * RATE).toInt()), RATE)

    private fun kick(x: FloatArray, at: Int, gain: Float) {
        val len = (0.14f * RATE).toInt()
        for (i in 0 until len) {
            val n = at + i
            if (n < 0 || n >= x.size) continue
            val t = i.toDouble() / RATE
            // Pitch envelope from 110 Hz down to 45 Hz, like a real kick.
            val f = 45.0 + 65.0 * exp(-t * 45)
            val env = exp(-t * 22).toFloat()
            x[n] += (sin(2 * PI * f * t) * env * gain).toFloat()
        }
    }

    private fun snare(x: FloatArray, at: Int, rng: Random, gain: Float) {
        val len = (0.11f * RATE).toInt()
        for (i in 0 until len) {
            val n = at + i
            if (n < 0 || n >= x.size) continue
            val t = i.toDouble() / RATE
            val env = exp(-t * 34).toFloat()
            val noise = rng.nextFloat() * 2f - 1f
            val body = sin(2 * PI * 190.0 * t).toFloat() * 0.4f
            x[n] += (noise * 0.75f + body) * env * gain
        }
    }

    private fun hat(x: FloatArray, at: Int, rng: Random, gain: Float) {
        val len = (0.035f * RATE).toInt()
        var prev = 0f
        for (i in 0 until len) {
            val n = at + i
            if (n < 0 || n >= x.size) continue
            val t = i.toDouble() / RATE
            val env = exp(-t * 120).toFloat()
            val white = rng.nextFloat() * 2f - 1f
            // Crude one-pole high pass so the hat sits above the snare in frequency.
            val hp = white - prev
            prev = white
            x[n] += hp * env * gain
        }
    }
}
