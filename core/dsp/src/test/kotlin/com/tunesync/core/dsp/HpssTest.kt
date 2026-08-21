package com.tunesync.core.dsp

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HPSS is the whole basis of "ignore the vocals", so it gets tested on its own
 * rather than only through the beat tracker.
 */
class HpssTest {

    /**
     * Spectral energy, not onset energy. The onset envelope is normalised to unit
     * variance, so summing it cannot show suppression however well separation works.
     */
    private fun retainedEnergy(sig: AudioSignal): Float {
        val spec = Stft.compute(sig.resampleTo(AudioSignal.ANALYSIS_RATE))
        val percussive = Hpss.percussive(spec)
        var before = 0.0
        var after = 0.0
        for (i in spec.data.indices) {
            before += spec.data[i]
            after += percussive.data[i]
        }
        return if (before > 0) (after / before).toFloat() else 0f
    }

    @Test
    fun `separation suppresses a sustained vocal far more than it suppresses drums`() {
        val vocalRetained = retainedEnergy(SyntheticAudio.vocalOnly(seconds = 20f))
        val drumRetained = retainedEnergy(SyntheticAudio.drumPattern(120f, bars = 10).signal)
        println("HPSS retains %.3f of vocal energy, %.3f of drum energy".format(vocalRetained, drumRetained))

        assertTrue(vocalRetained < 0.20f, "a sustained vocal should be mostly removed, kept $vocalRetained")
        assertTrue(drumRetained > 0.50f, "drums must survive separation, kept $drumRetained")
        assertTrue(
            drumRetained > vocalRetained * 3f,
            "separation must strongly favour drums: $drumRetained vs $vocalRetained",
        )
    }

    @Test
    fun `adding a vocal barely changes the percussive content`() {
        val dry = SyntheticAudio.drumPattern(120f, bars = 10, withVocal = false).signal
        val wet = SyntheticAudio.drumPattern(120f, bars = 10, withVocal = true).signal

        val dryEnv = OnsetDetector.detect(Hpss.percussive(Stft.compute(dry.resampleTo(AudioSignal.ANALYSIS_RATE))))
        val wetEnv = OnsetDetector.detect(Hpss.percussive(Stft.compute(wet.resampleTo(AudioSignal.ANALYSIS_RATE))))

        // Correlate the two onset envelopes: the vocal should not change where the
        // transients are, only how loud everything is relative to full scale.
        val n = minOf(dryEnv.frames, wetEnv.frames)
        var dot = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            dot += dryEnv.total[i] * wetEnv.total[i]
            da += dryEnv.total[i] * dryEnv.total[i].toDouble()
            db += wetEnv.total[i] * wetEnv.total[i].toDouble()
        }
        val correlation = dot / (kotlin.math.sqrt(da) * kotlin.math.sqrt(db))
        println("onset envelope correlation with/without vocal: %.3f".format(correlation))
        assertTrue(correlation > 0.90, "the vocal changed the transient picture too much: $correlation")
    }
}
