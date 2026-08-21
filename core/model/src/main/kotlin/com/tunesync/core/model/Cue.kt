package com.tunesync.core.model

import kotlinx.serialization.Serializable

enum class Channel { TORCH, SCREEN, HAPTIC }

/** How a screen cue moves over its duration. The torch can only step. */
enum class Curve { STEP, DECAY, RAMP }

/**
 * One hardware event at one instant. Times are milliseconds from the start of
 * the track, already latency-compensated for the target device, so [timeMs]
 * means *photons out*, not *call made*.
 */
@Serializable
data class Cue(
    val timeMs: Int,
    val channel: Channel,
    val durationMs: Int,
    /** 0..1. Torch maps this to strength level; haptic to amplitude. */
    val strength: Float = 1f,
    /** Packed 0xRRGGBB, screen channel only. */
    val rgb: Int = 0xFFFFFF,
    val curve: Curve = Curve.STEP,
) {
    val endMs: Int get() = timeMs + durationMs

    /**
     * Whether this cue counts against the flash-rate ceiling.
     *
     * What Harding measures is the *rising* luminance transition, so only a curve
     * that rises gradually is exempt. STEP and DECAY both switch on instantly —
     * DECAY merely fades afterwards — so both count. Only RAMP does not.
     *
     * Defined once, here, because both safety gates must agree on it.
     */
    val isFlash: Boolean
        get() = when (channel) {
            Channel.TORCH -> true
            Channel.SCREEN -> curve != Curve.RAMP
            Channel.HAPTIC -> false
        }
}

/**
 * Compiler output that has **not** passed the flash limiter, and therefore
 * cannot be handed to the runtime. Only :core:safety can turn one of these
 * into an ArmedShow. This is the type-level half of the two-gate rule.
 */
@Serializable
data class UnsafeCueList(
    val trackId: String,
    val durationMs: Long,
    val cues: List<Cue>,
) {
    init {
        require(cues.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs }) {
            "cues must be sorted by time before the limiter sees them"
        }
    }

    /** Peak flashes per second in any 1 s window. */
    fun peakFlashRateHz(): Float = peakRateIn(cues)

    companion object {
        const val WINDOW_MS = 1000

        /**
         * Cues closer together than this are one flash as far as an eye is
         * concerned, so the torch and the screen firing on the same beat count once.
         */
        const val FLASH_MERGE_MS = 25

        /**
         * The distinct instants at which light appears, in ascending order.
         *
         * Rate has to be measured in perceived flashes, not cues. Counting a
         * simultaneous torch and screen cue as two would have the limiter delete
         * half of every show the moment a user enables both channels — while
         * measuring no real change in what anyone sees.
         */
        fun flashInstants(cues: List<Cue>): IntArray {
            val times = cues.asSequence().filter { it.isFlash }.map { it.timeMs }.sorted().toList()
            if (times.isEmpty()) return IntArray(0)
            val out = ArrayList<Int>(times.size)
            var current = times[0]
            out.add(current)
            for (t in times) {
                if (t - current > FLASH_MERGE_MS) {
                    current = t
                    out.add(t)
                }
            }
            return out.toIntArray()
        }

        /**
         * Shared by the compiler, the limiter and the scheduler's second gate, so
         * no caller can disagree about what the rate is.
         */
        fun peakRateIn(cues: List<Cue>): Float = peakRateOf(flashInstants(cues))

        fun peakRateOf(instants: IntArray): Float {
            if (instants.size < 2) return instants.size.toFloat()
            var peak = 0
            var lo = 0
            for (hi in instants.indices) {
                while (instants[hi] - instants[lo] >= WINDOW_MS) lo++
                val count = hi - lo + 1
                if (count > peak) peak = count
            }
            return peak.toFloat()
        }
    }
}
