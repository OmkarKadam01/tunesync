package com.tunesync.core.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriftTrackerTest {

    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun `predicts nothing before any measurement`() {
        val t = DriftTracker()
        assertNull(t.positionAt(ms(0)))
        assertEquals(LockState.SEARCHING, t.stateAt(ms(0)))
    }

    @Test
    fun `locks after a couple of agreeing measurements`() {
        val t = DriftTracker()
        t.add(ms(0), 1_000f, 0.9f)
        assertEquals(LockState.SEARCHING, t.stateAt(ms(0)))
        t.add(ms(500), 1_500f, 0.9f)
        assertEquals(LockState.LOCKED, t.stateAt(ms(500)))
    }

    @Test
    fun `interpolates between measurements at nominal rate`() {
        val t = DriftTracker()
        t.add(ms(0), 10_000f, 1f)
        t.add(ms(500), 10_500f, 1f)

        val at750 = t.positionAt(ms(750))
        assertNotNull(at750)
        assertTrue(abs(at750 - 10_750f) < 5f, "expected ~10750, got $at750")
    }

    @Test
    fun `recovers a playback rate faster than nominal`() {
        // A DJ running the track 4% fast.
        val t = DriftTracker()
        val rate = 1.04f
        for (i in 0..10) {
            val wall = i * 500L
            t.add(ms(wall), 5_000f + rate * wall, 1f)
        }
        assertTrue(abs(t.rate - rate) < 0.01f, "recovered rate ${t.rate}, expected $rate")
    }

    @Test
    fun `clamps an implausible rate rather than sprinting off the end`() {
        val t = DriftTracker()
        for (i in 0..10) {
            val wall = i * 500L
            // 3x speed is not a pitch fader; it is a different signal.
            t.add(ms(wall), 3f * wall, 1f)
        }
        assertTrue(t.rate <= 1.08f, "rate ${t.rate} should be clamped")
    }

    @Test
    fun `tracks slow crystal drift without twitching`() {
        // 50 ppm is 3 ms per minute — small, but it accumulates across a set.
        val t = DriftTracker()
        val rate = 1.00005f
        for (i in 0..20) {
            val wall = i * 500L
            t.add(ms(wall), rate * wall, 0.9f)
        }
        val predicted = t.positionAt(ms(60_000))!!
        assertTrue(abs(predicted - 60_003f) < 30f, "predicted $predicted at one minute")
    }

    @Test
    fun `rejects a single wild measurement`() {
        val t = DriftTracker()
        t.add(ms(0), 10_000f, 1f)
        t.add(ms(500), 10_500f, 1f)
        t.add(ms(1000), 11_000f, 1f)

        val accepted = t.add(ms(1500), 90_000f, 1f)
        assertFalse(accepted, "a 79-second jump should be rejected")

        val predicted = t.positionAt(ms(1500))!!
        assertTrue(abs(predicted - 11_500f) < 50f, "prediction was corrupted: $predicted")
    }

    @Test
    fun `re-seeks after a run of rejections`() {
        // The operator skipped to a different part of the track: prediction is now
        // wrong, and holding onto it forever would be worse than re-locking.
        val t = DriftTracker(rejectionsBeforeReset = 3)
        t.add(ms(0), 10_000f, 1f)
        t.add(ms(500), 10_500f, 1f)

        var accepted = false
        for (i in 1..4) {
            accepted = t.add(ms(1000 + i * 500L), 90_000f + i * 500f, 1f)
        }
        assertTrue(accepted, "should eventually accept the new reality")
        val predicted = t.positionAt(ms(3000))!!
        assertTrue(predicted > 80_000f, "should have re-seeked, predicted $predicted")
    }

    @Test
    fun `coasts then declares loss`() {
        val t = DriftTracker(coastLimitMs = 8_000)
        t.add(ms(0), 5_000f, 1f)
        t.add(ms(500), 5_500f, 1f)

        assertEquals(LockState.LOCKED, t.stateAt(ms(1_000)))
        assertEquals(LockState.COASTING, t.stateAt(ms(4_000)))
        assertEquals(LockState.LOST, t.stateAt(ms(10_000)))

        // Still predicting while coasting: a crowd roar over one chorus must not
        // kill the show.
        assertNotNull(t.positionAt(ms(4_000)))
    }

    @Test
    fun `low confidence measurements are ignored`() {
        val t = DriftTracker()
        assertFalse(t.add(ms(0), 1_000f, 0f))
        assertEquals(LockState.SEARCHING, t.stateAt(ms(0)))
    }

    @Test
    fun `reset clears everything`() {
        val t = DriftTracker()
        t.add(ms(0), 1_000f, 1f)
        t.add(ms(500), 1_500f, 1f)
        t.reset()
        assertNull(t.positionAt(ms(1_000)))
        assertEquals(0, t.measurements)
        assertEquals(LockState.SEARCHING, t.stateAt(ms(1_000)))
    }

    @Test
    fun `the window bounds memory use`() {
        val t = DriftTracker(maxMeasurements = 8)
        for (i in 0..100) t.add(ms(i * 500L), i * 500f, 1f)
        assertEquals(8, t.measurements)
    }
}
