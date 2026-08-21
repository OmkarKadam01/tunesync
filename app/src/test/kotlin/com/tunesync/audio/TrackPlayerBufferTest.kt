package com.tunesync.audio

import com.tunesync.audio.PcmBuffer.FRAME_BYTES
import com.tunesync.audio.PcmBuffer.sizeBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioTrack.Builder rejects a buffer that is not a whole number of frames with
 * an opaque "Invalid audio buffer size" at build() — a hard crash rather than a
 * degraded stream. The arithmetic lives in PcmBuffer so it is checkable here
 * instead of only on a device.
 */
class TrackPlayerBufferTest {

    private val commonRates = intArrayOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000)

    @Test
    fun `buffer is always a whole number of frames`() {
        // Includes the sentinels getMinBufferSize can return, and awkward values
        // that are not already frame-aligned.
        val reportedValues = intArrayOf(-2, -1, 0, 1, 3, 7, 11025, 3528, 4801, 7104)
        for (rate in commonRates) {
            for (reported in reportedValues) {
                val bytes = sizeBytes(rate, reported)
                assertEquals(
                    "rate=$rate reported=$reported produced $bytes, not frame-aligned",
                    0,
                    bytes % FRAME_BYTES,
                )
                assertTrue("rate=$rate reported=$reported produced $bytes", bytes > 0)
            }
        }
    }

    @Test
    fun `error sentinels fall back to about a quarter second`() {
        for (rate in commonRates) {
            for (sentinel in intArrayOf(-2, -1, 0)) {
                val frames = sizeBytes(rate, sentinel) / FRAME_BYTES
                val ms = frames * 1000f / rate
                assertTrue("rate=$rate sentinel=$sentinel gave ${ms}ms of buffer", ms in 200f..300f)
            }
        }
    }

    @Test
    fun `a valid minimum is doubled for double buffering`() {
        // 22050 bytes is already frame-aligned and above the floor, so doubling lands exactly.
        assertEquals(44100, sizeBytes(44100, 22050))
    }

    @Test
    fun `never returns less than the quarter second floor`() {
        // A tiny reported minimum must not produce a buffer that underruns instantly.
        val bytes = sizeBytes(44100, 16)
        assertTrue("got $bytes bytes", bytes >= 44100 / 4 * FRAME_BYTES)
    }

    @Test
    fun `the exact size that crashed on device is no longer produced`() {
        // The old code used rate/4 as if it were bytes, then doubled: 22050 bytes,
        // which is 5512.5 frames of mono float.
        assertEquals(2, (44100 / 4 * 2) % FRAME_BYTES)
        for (rate in commonRates) {
            assertEquals(0, sizeBytes(rate, -2) % FRAME_BYTES)
        }
    }
}
