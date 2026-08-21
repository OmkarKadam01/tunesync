package com.tunesync.core.safety

import com.tunesync.core.model.Cue
import com.tunesync.core.model.UnsafeCueList

/**
 * A cue list that has passed the flash limiter and is the only thing the show
 * runner accepts. The constructor is internal to this module, so there is no
 * path from a compiled [UnsafeCueList] to the hardware that skips [FlashLimiter].
 */
class ArmedShow internal constructor(
    val trackId: String,
    val durationMs: Long,
    val cues: List<Cue>,
    val report: LimiterReport,
    val policy: FlashPolicy,
) {
    /** Flattened to a primitive array so the scheduler never touches an iterator. */
    val timesMs: IntArray = IntArray(cues.size) { cues[it].timeMs }

    val peakFlashRateHz: Float get() = report.finalPeakRateHz

    /** Index of the first cue at or after [fromMs]; the runner seeks with this. */
    fun indexAt(fromMs: Int): Int {
        var lo = 0
        var hi = timesMs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timesMs[mid] < fromMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Gate two. The scheduler calls this immediately before output starts: one
     * gate can be bypassed by a bug, two independent ones are much less likely to be.
     */
    fun verifyOrThrow() {
        val rate = UnsafeCueList.peakRateIn(cues)
        check(rate <= policy.maxFlashesPerWindow) {
            "refusing to arm: $rate flashes/s exceeds the ${policy.maxFlashesPerWindow} Hz ceiling"
        }
        check(timesMs.size == cues.size) { "cue index is stale" }
    }
}
