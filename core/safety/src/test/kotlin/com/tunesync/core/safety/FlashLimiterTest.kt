package com.tunesync.core.safety

import com.tunesync.core.model.Channel
import com.tunesync.core.model.Cue
import com.tunesync.core.model.Curve
import com.tunesync.core.model.UnsafeCueList
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one suite that gates release unconditionally. If the cap can be exceeded
 * for any tempo, any style and any edit set, nothing ships.
 */
class FlashLimiterTest {

    private fun clickTrack(
        bpm: Float,
        beats: Int = 240,
        channel: Channel = Channel.TORCH,
        strengths: (Int) -> Float = { 1f },
    ): UnsafeCueList {
        val periodMs = 60_000f / bpm
        val cues = (0 until beats).map { i ->
            Cue(
                // Round rather than truncate: at 180 bpm, truncation puts the
                // fourth beat at 999 ms and manufactures a cap violation that
                // the music does not actually contain.
                timeMs = (i * periodMs).roundToInt(),
                channel = channel,
                durationMs = 60,
                strength = strengths(i),
            )
        }
        return UnsafeCueList("test", (beats * periodMs).toLong(), cues)
    }

    private fun assertWithinCap(show: ArmedShow, policy: FlashPolicy = FlashPolicy.DEFAULT) {
        val rate = UnsafeCueList.peakRateIn(show.cues)
        assertTrue(
            rate <= policy.maxFlashesPerWindow,
            "peak $rate/s exceeds ${policy.maxFlashesPerWindow}/s cap",
        )
        show.verifyOrThrow()
    }

    @Test
    fun `every tempo from 40 to 300 bpm stays within the cap`() {
        var bpm = 40f
        while (bpm <= 300f) {
            val show = FlashLimiter.arm(clickTrack(bpm))
            assertWithinCap(show)
            bpm += 0.5f
        }
    }

    @Test
    fun `180 bpm is the boundary and passes untouched`() {
        // 3 Hz exactly. Flashing on every beat is legal right up to here.
        val show = FlashLimiter.arm(clickTrack(180f))
        assertWithinCap(show)
        assertEquals(0, show.report.droppedCount, "180 bpm should need no trimming")
    }

    @Test
    fun `above 180 bpm the limiter recommends a wider spacing`() {
        val show = FlashLimiter.arm(clickTrack(240f))
        assertWithinCap(show)
        assertTrue(show.report.recommendEscalation, "240 bpm should trigger escalation advice")
    }

    @Test
    fun `dropped cues are the weakest ones, not every other one`() {
        // Alternating strong downbeat / weak offbeat at a tempo that must be thinned.
        val show = FlashLimiter.arm(
            clickTrack(240f, beats = 64) { if (it % 4 == 0) 1.0f else 0.2f },
        )
        assertWithinCap(show)
        val survivingStrong = show.cues.count { it.strength > 0.9f }
        val survivingWeak = show.cues.count { it.strength < 0.3f }
        assertTrue(
            survivingStrong > survivingWeak,
            "kept $survivingStrong strong vs $survivingWeak weak — the musical hits should survive",
        )
    }

    @Test
    fun `random irregular cue lists never exceed the cap`() {
        val rng = Random(20260821)
        repeat(400) { seed ->
            val n = rng.nextInt(2, 300)
            val times = generateSequence(0) { it + rng.nextInt(20, 900) }
                .take(n)
                .toList()
            val cues = times.map {
                Cue(
                    timeMs = it,
                    channel = if (rng.nextBoolean()) Channel.TORCH else Channel.SCREEN,
                    durationMs = rng.nextInt(20, 150),
                    strength = rng.nextFloat(),
                    curve = if (rng.nextInt(4) == 0) Curve.RAMP else Curve.STEP,
                )
            }
            val show = FlashLimiter.arm(UnsafeCueList("rnd$seed", times.last().toLong(), cues))
            assertWithinCap(show)
        }
    }

    @Test
    fun `a track that sits at the cap keeps its cues and is only flagged`() {
        // 170 bpm on every beat is 2.83 Hz — legal, but continuously near the
        // ceiling. The limiter must report that, not delete the show.
        val show = FlashLimiter.arm(clickTrack(170f, beats = 120))
        assertWithinCap(show)
        assertEquals(120, show.cues.size, "a legal at-cap track must survive intact")
        assertTrue(
            show.report.sustainedAtCapMs > 20_000,
            "expected a long at-cap stretch to be reported, got ${show.report.sustainedAtCapMs} ms",
        )
    }

    @Test
    fun `a sparse track reports no sustained at-cap stretch`() {
        val show = FlashLimiter.arm(clickTrack(60f, beats = 60))
        assertEquals(0, show.report.sustainedAtCapMs)
    }

    @Test
    fun `screen decay cues count against the cap`() {
        // A DECAY cue switches on instantly and only fades afterwards, so its
        // rising edge is a flash. Exempting it would leave a screen-only show
        // with no rate limit at all.
        val cues = (0 until 40).map {
            Cue(it * 100, Channel.SCREEN, durationMs = 80, curve = Curve.DECAY)
        }
        val show = FlashLimiter.arm(UnsafeCueList("decay", 4000, cues))
        assertWithinCap(show)
        assertTrue(show.cues.size < 40, "a 10 Hz decay train must be thinned, kept ${show.cues.size}")
    }

    @Test
    fun `torch and screen on the same beat count as one flash`() {
        // Otherwise enabling both output channels would halve every show while
        // changing nothing about what a person actually sees.
        val beats = 40
        val period = 60_000f / 150f
        val cues = (0 until beats).flatMap { i ->
            val t = (i * period).roundToInt()
            listOf(
                Cue(t, Channel.TORCH, durationMs = 60),
                Cue(t, Channel.SCREEN, durationMs = 90, curve = Curve.DECAY),
            )
        }.sortedBy { it.timeMs }

        val both = FlashLimiter.arm(UnsafeCueList("both", 20_000, cues))
        val torchOnly = FlashLimiter.arm(
            UnsafeCueList("torch", 20_000, cues.filter { it.channel == Channel.TORCH }),
        )

        assertWithinCap(both)
        assertEquals(
            torchOnly.report.finalFlashCount,
            both.report.finalFlashCount,
            "adding the screen channel must not change the flash count",
        )
        assertEquals(beats, both.report.finalFlashCount, "150 bpm is 2.5 Hz and should survive intact")
        assertEquals(beats * 2, both.cues.size, "both channels should still be present")
    }

    @Test
    fun `a dropped instant takes every channel with it`() {
        // Silencing the torch but leaving the screen firing would look like a
        // rate reduction without being one.
        val period = 60_000f / 300f
        val cues = (0 until 60).flatMap { i ->
            val t = (i * period).roundToInt()
            listOf(
                Cue(t, Channel.TORCH, durationMs = 40, strength = if (i % 4 == 0) 1f else 0.2f),
                Cue(t, Channel.SCREEN, durationMs = 40, strength = if (i % 4 == 0) 1f else 0.2f),
            )
        }.sortedBy { it.timeMs }

        val show = FlashLimiter.arm(UnsafeCueList("pairs", 12_000, cues))
        assertWithinCap(show)

        val byTime = show.cues.groupBy { it.timeMs }
        for ((time, group) in byTime) {
            assertEquals(
                2,
                group.size,
                "instant at $time ms kept ${group.size} cue(s) — channels must be dropped together",
            )
        }
    }

    @Test
    fun `smooth screen ramps are not counted as flashes`() {
        val ramps = (0 until 40).map {
            Cue(it * 100, Channel.SCREEN, durationMs = 100, curve = Curve.RAMP)
        }
        val show = FlashLimiter.arm(UnsafeCueList("ramp", 4000, ramps))
        assertEquals(40, show.cues.size, "a 10 Hz smooth ramp is a luminance change, not a flash")
    }

    @Test
    fun `haptics are never limited`() {
        val haptics = (0 until 60).map {
            Cue(it * 100, Channel.HAPTIC, durationMs = 20)
        }
        val show = FlashLimiter.arm(UnsafeCueList("hap", 6000, haptics))
        assertEquals(60, show.cues.size)
    }

    @Test
    fun `saturated red is rotated out of the palette`() {
        val red = Cue(0, Channel.SCREEN, durationMs = 200, rgb = 0xFF0000)
        val show = FlashLimiter.arm(UnsafeCueList("red", 1000, listOf(red)))
        val out = show.cues.single()
        val g = (out.rgb shr 8) and 0xFF
        assertTrue(g > 0x40, "expected red to be lifted toward amber, got ${out.rgb.toString(16)}")
    }

    @Test
    fun `the cap cannot be configured upward`() {
        val threw = try {
            FlashPolicy(maxFlashesPerWindow = 8); false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(threw, "FlashPolicy must reject a ceiling above 3 Hz")
    }

    @Test
    fun `reduced mode holds one flash per second`() {
        val show = FlashLimiter.arm(clickTrack(128f), FlashPolicy.REDUCED)
        assertWithinCap(show, FlashPolicy.REDUCED)
    }

    @Test
    fun `empty and single cue lists are handled`() {
        assertEquals(0, FlashLimiter.arm(UnsafeCueList("e", 0, emptyList())).cues.size)
        val one = listOf(Cue(0, Channel.TORCH, 60))
        assertEquals(1, FlashLimiter.arm(UnsafeCueList("s", 100, one)).cues.size)
    }

    @Test
    fun `indexAt seeks correctly`() {
        val show = FlashLimiter.arm(clickTrack(120f, beats = 10))
        assertEquals(0, show.indexAt(0))
        assertEquals(1, show.indexAt(1))
        assertEquals(show.cues.size, show.indexAt(Int.MAX_VALUE))
    }
}
