package com.tunesync.core.listen

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log

/**
 * Continuous microphone capture into a ring buffer.
 *
 * The source matters more than anything else here. The default `MIC` source
 * runs automatic gain control and noise suppression, both of which are tuned to
 * make speech intelligible and both of which mangle exactly the spectral
 * structure fingerprinting depends on. `UNPROCESSED` is requested wherever the
 * device advertises it.
 *
 * Audio is never written to disk and never leaves the process.
 */
class MicCapture(context: Context, private val ringSeconds: Float = 6f) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var sampleRate: Int = 0
        private set

    /** True when the device gave us a genuinely unprocessed stream. */
    @Volatile
    var unprocessed: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private lateinit var ring: FloatArray
    private var ringSize = 0

    /** Total frames ever written; the ring holds the most recent [ringSize] of them. */
    @Volatile
    private var written = 0L

    // Anchor tying a capture frame index to the instant it actually entered the
    // microphone, on the same clock the scheduler uses.
    @Volatile
    private var anchorFrame = -1L

    @Volatile
    private var anchorNanos = 0L

    @Volatile
    var haveCaptureTimestamps = false
        private set

    private val lock = Any()

    /**
     * @return true if capture started. False means either the permission is
     *   missing or no usable configuration exists on this device.
     */
    fun start(): Boolean {
        stop()
        lastError = null

        for (rate in CANDIDATE_RATES) {
            val minBytes = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0) continue

            val bufferBytes = maxOf(minBytes * 4, rate * BYTES_PER_FRAME / 2)
            for (source in candidateSources()) {
                val r = try {
                    @Suppress("MissingPermission")
                    AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                } catch (e: SecurityException) {
                    lastError = "microphone permission not granted"
                    return false
                } catch (e: IllegalArgumentException) {
                    continue
                }

                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    r.release()
                    continue
                }

                sampleRate = rate
                unprocessed = source == MediaRecorder.AudioSource.UNPROCESSED
                ringSize = (rate * ringSeconds).toInt()
                ring = FloatArray(ringSize)
                written = 0
                record = r
                running = true

                return try {
                    r.startRecording()
                    if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        throw IllegalStateException("recorder did not start")
                    }
                    thread = Thread(::readLoop, "tunesync-mic").apply {
                        priority = Thread.MAX_PRIORITY
                        start()
                    }
                    true
                } catch (e: Exception) {
                    lastError = "could not start the microphone"
                    Log.w(TAG, "startRecording failed", e)
                    stop()
                    false
                }
            }
        }
        if (lastError == null) lastError = "no usable microphone configuration"
        return false
    }

    fun stop() {
        running = false
        thread?.join(120)
        thread = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
    }

    val isRunning: Boolean get() = running

    /** Frames captured so far. Used to timestamp a window against the ring. */
    fun framesWritten(): Long = written

    /**
     * Copy the most recent `out.size` frames into [out].
     *
     * @return the index of the first frame copied, counted from the start of
     *   capture, or -1 when not enough audio has accumulated yet. The caller
     *   needs that index to know what wall-clock instant the window begins at.
     */
    fun readRecent(out: FloatArray): Long {
        synchronized(lock) {
            val total = written
            if (total < out.size) return -1
            val startFrame = total - out.size
            var src = (((startFrame % ringSize) + ringSize) % ringSize).toInt()
            for (i in out.indices) {
                out[i] = ring[src]
                src++
                if (src == ringSize) src = 0
            }
            return startFrame
        }
    }

    /**
     * When [frame] entered the microphone, on the scheduler's clock.
     *
     * Assuming the newest frame arrived "now" would ignore the input buffer —
     * typically 20–80 ms — and make every cue systematically late by that much,
     * which is most of the timing budget. `AudioRecord.getTimestamp` reports when
     * audio genuinely arrived, so the offset is measured rather than guessed.
     *
     * Falls back to an estimate on devices that decline to report timestamps.
     */
    fun captureNanosOf(frame: Long): Long {
        val rate = sampleRate
        val anchor = anchorFrame
        if (anchor >= 0 && rate > 0) {
            return anchorNanos + (frame - anchor) * 1_000_000_000L / rate
        }
        // No timestamp support: assume the most recent frame is one buffer old.
        val behind = written - frame + (rate * FALLBACK_LATENCY_SECONDS).toLong()
        return SystemClock.elapsedRealtimeNanos() - behind * 1_000_000_000L / maxOf(rate, 1)
    }

    private fun readLoop() {
        val r = record ?: return
        val chunk = ShortArray(2048)
        val timestamp = AudioTimestamp()
        while (running) {
            val n = try {
                r.read(chunk, 0, chunk.size)
            } catch (e: IllegalStateException) {
                break
            }
            if (n <= 0) {
                // ERROR_INVALID_OPERATION means the recorder was stopped under us;
                // anything else transient is worth one more turn of the loop.
                if (n == AudioRecord.ERROR_INVALID_OPERATION) break
                continue
            }
            synchronized(lock) {
                var dst = (((written % ringSize) + ringSize) % ringSize).toInt()
                for (i in 0 until n) {
                    ring[dst] = chunk[i] / 32768f
                    dst++
                    if (dst == ringSize) dst = 0
                }
                written += n
            }

            // Re-anchor a few times a second. nanoTime here is CLOCK_MONOTONIC while
            // the scheduler runs on CLOCK_BOOTTIME, so it is converted rather than
            // compared across timebases.
            if (r.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) ==
                AudioRecord.SUCCESS
            ) {
                anchorFrame = timestamp.framePosition
                anchorNanos = timestamp.nanoTime +
                    (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
                haveCaptureTimestamps = true
            }
        }
        running = false
    }

    private fun candidateSources(): List<Int> {
        val supportsUnprocessed =
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return buildList {
            if (supportsUnprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
            // Still better than MIC: less aggressive gain control on most devices.
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
    }

    private companion object {
        const val TAG = "MicCapture"
        const val BYTES_PER_FRAME = 2

        /** Mid-range input latency, used only when the device reports no timestamps. */
        const val FALLBACK_LATENCY_SECONDS = 0.04f

        val CANDIDATE_RATES = intArrayOf(44100, 48000, 22050)
    }
}
