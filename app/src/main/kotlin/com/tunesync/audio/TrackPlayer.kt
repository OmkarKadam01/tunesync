package com.tunesync.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import com.tunesync.core.dsp.AudioSignal
import com.tunesync.core.output.PositionSource

/**
 * Plays the decoded track and reports where it actually is.
 *
 * The position is the important part. Starting a timer when playback begins is
 * the obvious approach and it is wrong: AudioTrack buffers 50-200 ms depending
 * on device and route, and Bluetooth adds 150-250 ms on top. getTimestamp()
 * reports which frame is genuinely leaving the DAC, which is the only number
 * the light show can be scheduled against.
 */
class TrackPlayer(private val signal: AudioSignal) : PositionSource {

    private var track: AudioTrack? = null
    private var writer: Thread? = null

    @Volatile
    private var playing = false

    @Volatile
    private var framesWritten = 0L

    /** Linear fit of (frame position, nanoTime) sampled from getTimestamp(). */
    @Volatile
    private var anchorFrame = -1L

    @Volatile
    private var anchorNanos = 0L

    @Volatile
    var usingFallbackClock = false
        private set

    val durationMs: Long get() = signal.durationMs

    fun start(fromMs: Int = 0) {
        stop()
        val rate = signal.sampleRate
        val reported = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferBytes = PcmBuffer.sizeBytes(rate, reported)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = t
        anchorFrame = -1
        framesWritten = 0
        playing = true
        t.play()

        val startFrame = (fromMs.toLong() * rate / 1000).toInt().coerceIn(0, signal.samples.size)
        writer = Thread({ writeLoop(t, startFrame) }, "tunesync-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        playing = false
        // Panic stop has a 100 ms budget and this runs on the main thread, so the
        // writer gets a brief chance to notice the flag before we release under it.
        // Every teardown call below is guarded because it may not.
        writer?.join(60)
        writer = null
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
        anchorFrame = -1
    }

    val isPlaying: Boolean get() = playing

    private fun writeLoop(t: AudioTrack, startFrame: Int) {
        try {
            writeLoopInner(t, startFrame)
        } catch (e: IllegalStateException) {
            // stop() released the track while a blocking write was in flight. That
            // is the normal shutdown race, not an error — but an escaping exception
            // on this thread would take the process down with it.
        } finally {
            playing = false
        }
    }

    private fun writeLoopInner(t: AudioTrack, startFrame: Int) {
        val x = signal.samples
        val chunk = 4096
        var offset = startFrame
        val timestamp = AudioTimestamp()

        while (playing && offset < x.size) {
            val count = minOf(chunk, x.size - offset)
            val written = t.write(x, offset, count, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
            framesWritten = (offset - startFrame).toLong()

            // Re-anchor on the real DAC position a few times a second. Between
            // anchors the position is extrapolated at the nominal sample rate.
            if (t.getTimestamp(timestamp)) {
                anchorFrame = timestamp.framePosition + startFrame
                // AudioTimestamp.nanoTime is CLOCK_MONOTONIC; the scheduler runs on
                // CLOCK_BOOTTIME. They drift apart by however long the device has
                // slept since boot, so the anchor is converted rather than compared
                // across timebases.
                anchorNanos = timestamp.nanoTime + monotonicToBootNanos()
                usingFallbackClock = false
            } else if (anchorFrame < 0) {
                usingFallbackClock = true
            }
        }
        playing = false
    }

    override fun positionMsAt(elapsedRealtimeNanos: Long): Float? {
        val t = track ?: return null
        if (!playing && anchorFrame < 0) return null
        val rate = signal.sampleRate

        val anchor = anchorFrame
        if (anchor >= 0) {
            val elapsedNanos = elapsedRealtimeNanos - anchorNanos
            val frames = anchor + elapsedNanos * rate / 1_000_000_000L
            return frames * 1000f / rate
        }

        // Fallback: playback head minus one buffer. Less accurate, and the session
        // is flagged so telemetry can tell these apart from properly clocked ones.
        val head = runCatching { t.playbackHeadPosition }.getOrDefault(0)
        return head * 1000f / rate
    }

    private companion object {
        /** Offset to add to a CLOCK_MONOTONIC reading to get CLOCK_BOOTTIME. */
        fun monotonicToBootNanos(): Long =
            SystemClock.elapsedRealtimeNanos() - System.nanoTime()
    }
}
