package com.tunesync.core.dsp

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Alignment, not identification: the index holds one track and the question is
 * only where in it the live audio is.
 */
class FingerprintTest {

    private val hopMs = Stft.HOP * 1000f / AudioSignal.ANALYSIS_RATE

    private fun indexOf(sig: AudioSignal) = FingerprintIndex.of(sig)

    /** An excerpt starting at [fromMs], as the microphone path would see it. */
    private fun excerpt(
        sig: AudioSignal,
        fromMs: Int,
        lengthMs: Int,
        noise: Float = 0f,
        gain: Float = 1f,
        seed: Int = 3,
    ): AudioSignal {
        val rate = sig.sampleRate
        val start = (fromMs.toLong() * rate / 1000).toInt().coerceIn(0, sig.samples.size)
        val end = (start + lengthMs.toLong() * rate / 1000).toInt().coerceAtMost(sig.samples.size)
        val out = sig.samples.copyOfRange(start, end)
        if (gain != 1f) for (i in out.indices) out[i] *= gain
        if (noise > 0f) {
            val rng = Random(seed)
            for (i in out.indices) out[i] += (rng.nextFloat() * 2f - 1f) * noise
        }
        return AudioSignal(out, rate)
    }

    private fun queryHashes(sig: AudioSignal): LongArray =
        Fingerprinter.hashes(Stft.compute(sig.resampleTo(AudioSignal.ANALYSIS_RATE)))

    private fun locatedMs(index: FingerprintIndex, query: AudioSignal): Float? {
        val m = index.match(queryHashes(query)) ?: return null
        return m.positionMsAtQueryStart(hopMs)
    }

    @Test
    fun `an excerpt is located within one frame of its true position`() {
        val track = SyntheticAudio.drumPattern(120f, bars = 40).signal
        val index = indexOf(track)

        for (fromMs in intArrayOf(4_000, 12_000, 20_000, 32_000)) {
            val found = locatedMs(index, excerpt(track, fromMs, 4_000))
            assertNotNull(found, "no match for an excerpt at $fromMs ms")
            assertTrue(
                abs(found - fromMs) <= hopMs * 1.5f,
                "excerpt at $fromMs ms located at $found ms",
            )
        }
    }

    @Test
    fun `location survives noise and a large level change`() {
        // A phone mic in a room: much quieter than the file, plus broadband noise.
        val track = SyntheticAudio.drumPattern(128f, bars = 40).signal
        val index = indexOf(track)
        val query = excerpt(track, 15_000, 4_000, noise = 0.04f, gain = 0.25f)

        val found = locatedMs(index, query)
        assertNotNull(found, "lost the track under noise")
        assertTrue(abs(found - 15_000) <= hopMs * 2f, "located at $found ms, expected ~15000")
    }

    @Test
    fun `audio from a different track does not produce a confident match`() {
        val index = indexOf(SyntheticAudio.drumPattern(120f, bars = 30).signal)
        // Different tempo and a different noise seed: genuinely other material.
        val other = SyntheticAudio.drumPattern(97f, bars = 30, seed = 99).signal
        val m = index.match(queryHashes(excerpt(other, 5_000, 4_000)))

        if (m != null) {
            assertTrue(
                m.votes < 12 || m.confidence < 0.35f,
                "a foreign track matched with ${m.votes} votes at confidence ${m.confidence}",
            )
        }
    }

    @Test
    fun `silence produces no match`() {
        val index = indexOf(SyntheticAudio.drumPattern(120f, bars = 20).signal)
        assertNull(index.match(queryHashes(SyntheticAudio.silence(4f))))
    }

    @Test
    fun `a correct match is far stronger than a foreign one`() {
        val track = SyntheticAudio.drumPattern(120f, bars = 30).signal
        val index = indexOf(track)
        val right = index.match(queryHashes(excerpt(track, 10_000, 4_000)))
        val wrong = index.match(queryHashes(excerpt(SyntheticAudio.drumPattern(97f, bars = 30, seed = 42).signal, 10_000, 4_000)))

        assertNotNull(right)
        val wrongVotes = wrong?.votes ?: 0
        println("correct: ${right.votes} votes conf ${right.confidence} | foreign: $wrongVotes votes")
        assertTrue(
            right.votes > wrongVotes * 3,
            "correct match (${right.votes}) should dominate a foreign one ($wrongVotes)",
        )
    }

    @Test
    fun `constraining the search to a predicted window still finds the answer`() {
        val track = SyntheticAudio.drumPattern(140f, bars = 40).signal
        val index = indexOf(track)
        val trueMs = 18_000
        val query = queryHashes(excerpt(track, trueMs, 3_000))

        val expected = (trueMs / hopMs).roundToInt()
        val tolerance = (2_000 / hopMs).roundToInt()
        val m = index.match(query, expectedDelta = expected, tolerance = tolerance)

        assertNotNull(m, "constrained search found nothing")
        assertTrue(
            abs(m.positionMsAtQueryStart(hopMs) - trueMs) <= hopMs * 2f,
            "constrained search located ${m.positionMsAtQueryStart(hopMs)} ms",
        )
    }

    @Test
    fun `a constrained search never returns an offset outside its window`() {
        val track = SyntheticAudio.drumPattern(120f, bars = 40).signal
        val index = indexOf(track)
        val query = queryHashes(excerpt(track, 30_000, 3_000))

        val expected = (2_000 / hopMs).roundToInt()
        val tolerance = (1_000 / hopMs).roundToInt()
        val m = index.match(query, expectedDelta = expected, tolerance = tolerance)

        // The audio is 30 s in but we claim to be near the start. The guarantee is
        // not that this finds nothing — a bar-length loop genuinely matches at many
        // offsets — but that whatever it returns respects the window it was given.
        if (m != null) {
            assertTrue(
                abs(m.deltaFrames - expected) <= tolerance,
                "returned delta ${m.deltaFrames} is outside the window $expected ± $tolerance",
            )
        }
    }

    @Test
    fun `repetitive material aligns at multiple offsets a bar apart`() {
        // Worth pinning down, because it is why alignment cannot rest on the
        // matcher alone: on a loop, several offsets are equally correct and only
        // temporal continuity (DriftTracker) picks between them.
        val track = SyntheticAudio.drumPattern(120f, bars = 40).signal
        val index = indexOf(track)
        val barMs = 4 * 60_000f / 120f

        val m = index.match(queryHashes(excerpt(track, 20_000, 3_000)))
        assertNotNull(m)
        val located = m.positionMsAtQueryStart(hopMs)
        val errorInBars = (located - 20_000) / barMs
        println("located at $located ms, ${"%.2f".format(errorInBars)} bars from truth")
        assertTrue(
            abs(errorInBars - errorInBars.roundToInt()) < 0.1f,
            "on a loop, any error should be a whole number of bars, was $errorInBars",
        )
    }

    @Test
    fun `index size stays within budget for a four minute track`() {
        val track = SyntheticAudio.drumPattern(128f, bars = 128).signal
        val index = indexOf(track)
        val kb = index.approximateBytes / 1024
        println("index: ${index.size} hashes, ~$kb KB for ${track.durationMs / 1000}s")
        assertTrue(kb < 2048, "index is ${kb} KB, too large to keep several resident")
        assertTrue(index.size > 1000, "index has only ${index.size} hashes, too sparse to match")
    }
}
