package com.tunesync.core.listen

import android.os.Process
import android.os.SystemClock
import com.tunesync.core.dsp.AudioSignal
import com.tunesync.core.dsp.DriftTracker
import com.tunesync.core.dsp.FingerprintIndex
import com.tunesync.core.dsp.Fingerprinter
import com.tunesync.core.dsp.LockState
import com.tunesync.core.dsp.Stft
import com.tunesync.core.output.PositionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class AlignStatus(
    val state: LockState = LockState.SEARCHING,
    /** Track position in ms when locked or coasting, else null. */
    val positionMs: Float? = null,
    /** 0..1 from the most recent match. */
    val confidence: Float = 0f,
    /** Playback rate relative to the reference. 1.0 is nominal. */
    val rate: Float = 1f,
    val matchesAccepted: Int = 0,
    /** Set when the microphone is receiving essentially nothing. */
    val micSilent: Boolean = false,
    val unprocessedMic: Boolean = false,
    val error: String? = null,
)

/**
 * Follows a live source playing a track we already hold a fingerprint for.
 *
 * This is the whole of Phase 2's consumer path. It is not song identification:
 * the index holds the one track the user imported, and the only question is
 * where in it the room currently is. Everything runs on-device with no network.
 */
class LiveAligner(
    private val index: FingerprintIndex,
    private val trackDurationMs: Long,
) : PositionSource {

    private val drift = DriftTracker()
    private val _status = MutableStateFlow(AlignStatus())
    val status: StateFlow<AlignStatus> = _status.asStateFlow()

    private var thread: Thread? = null

    @Volatile
    private var running = false

    private var mic: MicCapture? = null

    fun start(capture: MicCapture): Boolean {
        stop()
        drift.reset()
        _status.value = AlignStatus()

        if (!capture.start()) {
            _status.value = AlignStatus(error = capture.lastError ?: "microphone unavailable")
            return false
        }
        mic = capture
        running = true
        _status.value = AlignStatus(unprocessedMic = capture.unprocessed)
        thread = Thread(::loop, "tunesync-align").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(400)
        thread = null
        mic?.stop()
        mic = null
    }

    /**
     * Position for the show scheduler.
     *
     * Returns null while searching or after loss, which the runner reads as
     * "hold output dark" rather than guessing. Coasting still returns a
     * prediction — a crowd roar over one chorus must not kill the show.
     */
    override fun positionMsAt(elapsedRealtimeNanos: Long): Float? {
        return when (drift.stateAt(elapsedRealtimeNanos)) {
            LockState.LOCKED, LockState.COASTING ->
                drift.positionAt(elapsedRealtimeNanos)?.coerceIn(0f, trackDurationMs.toFloat())
            else -> null
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val capture = mic ?: return
        val hopMs = index.hopMs

        // The window has to be long enough to carry a distinctive set of peak
        // pairs and short enough that the position it reports is still current.
        var window: FloatArray? = null

        while (running) {
            val rate = capture.sampleRate
            if (rate <= 0) {
                Thread.sleep(POLL_MS)
                continue
            }
            val needed = (rate * WINDOW_SECONDS).toInt()
            if (window == null || window.size != needed) window = FloatArray(needed)

            val startFrame = capture.readRecent(window)
            if (startFrame < 0) {
                // Still filling the ring after start.
                Thread.sleep(POLL_MS)
                continue
            }

            // When the window's first sample genuinely entered the microphone,
            // measured rather than assumed — see MicCapture.captureNanosOf.
            val windowStartNanos = capture.captureNanosOf(startFrame)

            if (isSilent(window)) {
                publish(silent = true, confidence = 0f)
                Thread.sleep(POLL_MS)
                continue
            }

            val signal = AudioSignal(window, rate).resampleTo(AudioSignal.ANALYSIS_RATE)
            val hashes = Fingerprinter.hashes(Stft.compute(signal))
            if (hashes.isEmpty()) {
                publish(silent = false, confidence = 0f)
                Thread.sleep(POLL_MS)
                continue
            }

            // Once locked, only confirm the predicted position. That turns a search
            // over the whole track into a check over a two-second window, which is
            // what makes this cadence affordable, and it also resolves the genuine
            // ambiguity of repetitive material in favour of continuity.
            val predicted = drift.positionAt(windowStartNanos)
            val locked = drift.stateAt(SystemClock.elapsedRealtimeNanos()) == LockState.LOCKED
            val result = if (locked && predicted != null) {
                index.match(
                    hashes,
                    expectedDelta = (predicted / hopMs).roundToInt(),
                    tolerance = (RELOCK_TOLERANCE_MS / hopMs).roundToInt(),
                ) ?: index.match(hashes)
            } else {
                index.match(hashes)
            }

            if (result != null && result.votes >= MIN_VOTES && result.confidence >= MIN_CONFIDENCE) {
                val positionMs = result.positionMsAtQueryStart(hopMs)
                drift.add(windowStartNanos, positionMs, result.confidence)
                publish(silent = false, confidence = result.confidence)
            } else {
                publish(silent = false, confidence = result?.confidence ?: 0f)
            }

            Thread.sleep(POLL_MS)
        }
    }

    private fun publish(silent: Boolean, confidence: Float) {
        val now = SystemClock.elapsedRealtimeNanos()
        val state = drift.stateAt(now)
        _status.value = AlignStatus(
            state = state,
            positionMs = positionMsAt(now),
            confidence = confidence,
            rate = drift.rate,
            matchesAccepted = drift.measurements,
            micSilent = silent,
            unprocessedMic = mic?.unprocessed == true,
        )
    }

    /** Guards against burning CPU matching a covered microphone. */
    private fun isSilent(x: FloatArray): Boolean {
        var sum = 0.0
        for (v in x) sum += v * v
        val rms = kotlin.math.sqrt(sum / x.size)
        return rms < SILENCE_RMS
    }

    private companion object {
        const val WINDOW_SECONDS = 4f
        const val POLL_MS = 500L
        const val RELOCK_TOLERANCE_MS = 2_000f
        const val MIN_VOTES = 12
        const val MIN_CONFIDENCE = 0.35f
        const val SILENCE_RMS = 0.0015
    }
}
