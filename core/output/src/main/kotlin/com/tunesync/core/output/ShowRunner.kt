package com.tunesync.core.output

import android.os.Process
import android.os.SystemClock
import com.tunesync.core.model.Channel
import com.tunesync.core.model.Cue
import com.tunesync.core.safety.ArmedShow
import java.util.concurrent.atomic.AtomicReference

/**
 * Where the show is right now, in track milliseconds.
 *
 * Phase 1 backs this with AudioTrack.getTimestamp(); Phase 2 will back it with
 * the fingerprint drift filter. The scheduler does not care which.
 */
fun interface PositionSource {
    /** Track position in ms at the given monotonic timestamp, or null if unknown. */
    fun positionMsAt(elapsedRealtimeNanos: Long): Float?
}

/** What the screen channel drives. Implemented by the UI, which owns the surface. */
fun interface ScreenSink {
    fun onCue(cue: Cue?)
}

enum class RunState { IDLE, ARMED, RUNNING, PAUSED, STOPPED }

/**
 * Executes a compiled show against a position source.
 *
 * Runs on one dedicated thread at urgent-audio priority that does nothing else,
 * holds a frozen cue array, and never allocates. Everything creative already
 * happened at compile time; this only decides *when*.
 */
class ShowRunner(
    private val torch: TorchDriver,
    private val haptics: HapticDriver,
    private val screen: ScreenSink,
) {
    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private val stateRef = AtomicReference(RunState.IDLE)
    val state: RunState get() = stateRef.get()

    @Volatile
    var onFinished: (() -> Unit)? = null

    @Volatile
    var onTorchFired: (() -> Unit)? = null

    /** Cues dropped for arriving too late, surfaced to telemetry. */
    @Volatile
    var droppedLate: Int = 0
        private set

    fun start(show: ArmedShow, position: PositionSource) {
        stop()
        // Gate two. One gate can be bypassed by a bug; two independent ones are
        // much less likely to be.
        show.verifyOrThrow()

        droppedLate = 0
        running = true
        stateRef.set(RunState.ARMED)
        thread = Thread({ loop(show, position) }, "tunesync-show").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Panic stop. Must silence every channel well inside 100 ms. */
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        torch.off()
        haptics.cancel()
        screen.onCue(null)
        if (stateRef.get() != RunState.IDLE) stateRef.set(RunState.STOPPED)
    }

    private fun loop(show: ArmedShow, position: PositionSource) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        stateRef.set(RunState.RUNNING)

        val times = show.timesMs
        val cues = show.cues
        val n = times.size
        var index = 0
        var torchOffAtNanos = 0L
        var hapticActive = false

        try {
            while (running && index < n) {
                val now = SystemClock.elapsedRealtimeNanos()

                if (torchOffAtNanos != 0L && now >= torchOffAtNanos) {
                    torch.off()
                    torchOffAtNanos = 0L
                }

                val pos = position.positionMsAt(now)
                if (pos == null) {
                    // No lock yet, or playback paused. Hold output dark and wait.
                    if (torchOffAtNanos != 0L) {
                        torch.off()
                        torchOffAtNanos = 0L
                    }
                    parkBriefly()
                    continue
                }

                // Drop anything already past. Late light reads as wrong rather than
                // late, so it is never worth firing.
                while (index < n && times[index] < pos - LATE_TOLERANCE_MS) {
                    index++
                    droppedLate++
                }
                if (index >= n) break

                val waitMs = times[index] - pos
                if (waitMs > COARSE_SLEEP_THRESHOLD_MS) {
                    // Sleep most of the way, leaving the tail to the spin below.
                    val sleep = ((waitMs - COARSE_SLEEP_THRESHOLD_MS).toLong()).coerceAtMost(20L)
                    if (sleep > 0) Thread.sleep(sleep)
                    continue
                }

                // Spin the last couple of milliseconds. Handler.postDelayed and
                // coroutine delay both overshoot by tens of ms under queue backlog
                // or a GC pause, which is the whole budget.
                val targetNanos = now + (waitMs * 1_000_000L).toLong()
                while (running && SystemClock.elapsedRealtimeNanos() < targetNanos) {
                    Thread.onSpinWait()
                }
                if (!running) break

                val cue = cues[index]
                when (cue.channel) {
                    Channel.TORCH -> {
                        if (torch.isAvailable) {
                            torch.on(cue.strength)
                            torchOffAtNanos = SystemClock.elapsedRealtimeNanos() +
                                cue.durationMs * 1_000_000L
                            onTorchFired?.invoke()
                        }
                    }
                    Channel.SCREEN -> screen.onCue(cue)
                    Channel.HAPTIC -> {
                        haptics.pulse(cue.durationMs, cue.strength)
                        hapticActive = true
                    }
                }
                index++

                if (torch.consumeStolenFlag()) torch.retryAvailability()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            torch.off()
            if (hapticActive) haptics.cancel()
            screen.onCue(null)
            if (running) stateRef.set(RunState.STOPPED)
            running = false
            onFinished?.invoke()
        }
    }

    private fun parkBriefly() {
        Thread.sleep(4)
    }

    private companion object {
        /** Past this, the eye reads the flash as wrong rather than delayed. */
        const val LATE_TOLERANCE_MS = 30f
        const val COARSE_SLEEP_THRESHOLD_MS = 2f
    }
}
