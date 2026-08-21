package com.tunesync.audio

/**
 * Buffer arithmetic for the playback track. Deliberately free of Android types
 * so it can be checked on the host — the failure mode it guards against is a
 * hard crash at `AudioTrack.Builder.build()`, which is an expensive place to
 * discover an off-by-four.
 */
object PcmBuffer {

    /** Mono PCM float: one channel, four bytes per sample. */
    const val FRAME_BYTES = 4

    /**
     * Buffer size in **bytes**, rounded to whole frames.
     *
     * Two things make this easy to get wrong. `AudioTrack.getMinBufferSize`
     * returns bytes, not frames, so a fallback expressed in frames is off by a
     * factor of four. And `AudioTrack.Builder` rejects any size that is not a
     * whole number of frames with an opaque "Invalid audio buffer size": 22050
     * bytes is 5512.5 frames of mono float, and that throws at build() rather
     * than degrading the stream.
     *
     * @param reported whatever getMinBufferSize returned, including its
     *   ERROR (-1) and ERROR_BAD_VALUE (-2) sentinels.
     */
    fun sizeBytes(sampleRate: Int, reported: Int): Int {
        // A quarter second, and the floor in every case. Generous, because latency
        // to first sound does not affect sync accuracy here — playback position
        // comes from AudioTrack.getTimestamp(), not from when we started writing.
        val quarterSecond = sampleRate / 4 * FRAME_BYTES
        val target = if (reported > 0) {
            // Double the platform minimum: one buffer in flight, one being filled.
            maxOf(reported * 2, quarterSecond)
        } else {
            // getMinBufferSize returned ERROR or ERROR_BAD_VALUE. The floor already
            // allows double buffering, so doubling it again only delays the start.
            quarterSecond
        }
        return ((target + FRAME_BYTES - 1) / FRAME_BYTES) * FRAME_BYTES
    }
}
