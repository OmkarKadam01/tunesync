package com.tunesync.core.safety

import com.tunesync.core.model.Channel
import com.tunesync.core.model.Cue
import com.tunesync.core.model.Curve
import com.tunesync.core.model.UnsafeCueList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The first of the two gates that keep output below the photosensitive-epilepsy
 * threshold. Runs at compile time, may delete cues, and cannot be disabled.
 *
 * Seizures are provoked most readily between 3 and 30 Hz, and a torch LED in a
 * dark room is the highest-contrast case there is. 3 Hz is exactly 180 BPM, so
 * this is a path that fires on real music, not a formality.
 */
object FlashLimiter {

    /**
     * The only way to produce an [ArmedShow]. Returns the limited cue list plus a
     * report of what had to change, which the UI surfaces rather than hiding.
     */
    fun arm(list: UnsafeCueList, policy: FlashPolicy = FlashPolicy.DEFAULT): ArmedShow {
        val (flashing, smooth) = list.cues.partition { it.isFlash }

        val originalRate = UnsafeCueList.peakRateIn(flashing)
        var kept = flashing.map { it.deRed(policy) }

        kept = thinToWindowRate(kept, policy)

        val finalRate = UnsafeCueList.peakRateIn(kept)

        // Counted in perceived flashes, not cues, so the report reads the same
        // whether the user has one output channel enabled or both.
        val originalFlashes = UnsafeCueList.flashInstants(flashing).size
        val finalFlashes = UnsafeCueList.flashInstants(kept).size
        val dropped = originalFlashes - finalFlashes

        // Gate 1 assertion. If this trips the algorithm above is wrong, and
        // shipping the show anyway is not an option.
        check(finalRate <= policy.maxFlashesPerWindow) {
            "limiter failed to reach the cap: $finalRate flashes/s > ${policy.maxFlashesPerWindow}"
        }

        val merged = (kept + smooth).sortedBy { it.timeMs }
        val report = LimiterReport(
            originalFlashCount = originalFlashes,
            finalFlashCount = finalFlashes,
            droppedCount = dropped,
            originalPeakRateHz = originalRate,
            finalPeakRateHz = finalRate,
            sustainedAtCapMs = sustainedAtCapMs(kept, policy),
            // Losing a fifth of the flashes means the subdivision is wrong, not that
            // the show needed trimming. The caller should recompile a step down.
            recommendEscalation = originalFlashes > 0 &&
                dropped.toFloat() / originalFlashes > policy.escalationThreshold,
        )
        return ArmedShow(list.trackId, list.durationMs, merged, report, policy)
    }

    /**
     * Enforce no more than [FlashPolicy.maxFlashesPerWindow] in any 1 s window.
     *
     * Works on flash *instants*, not cues: a beat that drives the torch and the
     * screen together is one flash, and dropping only one of its cues would leave
     * the other still flashing while pretending the rate had come down.
     *
     * When instants must go, the *weakest* one in the offending window goes. Naive
     * every-other-instant decimation would be equally safe and rhythmically wrong —
     * it would strip the downbeat as readily as a ghost note.
     */
    private fun thinToWindowRate(cues: List<Cue>, policy: FlashPolicy): List<Cue> {
        val instants = groupIntoInstants(cues)
        val limit = policy.maxFlashesPerWindow
        while (true) {
            val window = firstOverfullWindow(instants, limit) ?: break
            var weakest = window.first
            for (i in window.first..window.last) {
                if (instants[i].strength < instants[weakest].strength) weakest = i
            }
            instants.removeAt(weakest)
        }
        return instants.flatMap { it.cues }.sortedBy { it.timeMs }
    }

    /** One entry per perceived flash, carrying every cue that fires at that moment. */
    private class Instant(val timeMs: Int) {
        val cues = ArrayList<Cue>(2)

        /** The loudest channel decides — a bright torch hit is not made weak by a dim screen. */
        val strength: Float get() = cues.maxOf { it.strength }
    }

    private fun groupIntoInstants(cues: List<Cue>): ArrayList<Instant> {
        val out = ArrayList<Instant>(cues.size)
        var current: Instant? = null
        for (cue in cues.sortedBy { it.timeMs }) {
            val c = current
            if (c == null || cue.timeMs - c.timeMs > UnsafeCueList.FLASH_MERGE_MS) {
                current = Instant(cue.timeMs).also {
                    it.cues.add(cue)
                    out.add(it)
                }
            } else {
                c.cues.add(cue)
            }
        }
        return out
    }

    /** Index range of the earliest 1 s window holding more than [limit] instants. */
    private fun firstOverfullWindow(instants: List<Instant>, limit: Int): IntRange? {
        var lo = 0
        for (hi in instants.indices) {
            while (instants[hi].timeMs - instants[lo].timeMs >= UnsafeCueList.WINDOW_MS) lo++
            if (hi - lo + 1 > limit) return lo..hi
        }
        return null
    }

    /**
     * Longest stretch the show spends *at* the ceiling rather than below it.
     *
     * This is reported, not enforced. WCAG 2.3.1 permits three flashes per second
     * with no cap on how long that may continue, and an invented burst rule would
     * gut any show above about 150 bpm without evidence that it helps. What a long
     * at-cap stretch does mean is that the style is too dense for the tempo, which
     * is an artistic correction the UI can offer.
     */
    private fun sustainedAtCapMs(cues: List<Cue>, policy: FlashPolicy): Int {
        val instants = UnsafeCueList.flashInstants(cues)
        if (instants.size <= policy.maxFlashesPerWindow) return 0
        var longest = 0
        var runStart = -1
        var lo = 0
        for (hi in instants.indices) {
            while (instants[hi] - instants[lo] >= UnsafeCueList.WINDOW_MS) lo++
            val atCap = hi - lo + 1 >= policy.maxFlashesPerWindow
            if (atCap) {
                if (runStart < 0) runStart = instants[lo]
                longest = max(longest, instants[hi] - runStart)
            } else {
                runStart = -1
            }
        }
        return longest
    }

    /**
     * Saturated red transitions are disproportionately provocative, so red is
     * blocked in the palette rather than merely discouraged. Rotate an offending
     * colour toward amber and drop its saturation instead of refusing the show.
     */
    private fun Cue.deRed(policy: FlashPolicy): Cue {
        if (channel != Channel.SCREEN || !policy.blockSaturatedRed) return this
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val dominantRed = r > 140 && r - max(g, b) > 90
        if (!dominantRed) return this
        val lifted = min(255, (r * 0.62f).roundToInt() + max(g, b))
        return copy(rgb = (r shl 16) or (lifted shl 8) or (b.coerceAtLeast(lifted / 3)))
    }
}

data class FlashPolicy(
    /** Hard ceiling in any rolling 1 s window. WCAG 2.3.1 permits three. */
    val maxFlashesPerWindow: Int = 3,
    /** Styles are authored below this so the limiter is an exception path. */
    val targetRateHz: Float = 2.5f,
    /**
     * Beyond this much continuous at-cap output the UI suggests a wider spacing.
     * Advisory: nothing is deleted on account of it.
     */
    val sustainedAtCapAdviceMs: Int = 20_000,
    val escalationThreshold: Float = 0.20f,
    val blockSaturatedRed: Boolean = true,
) {
    init {
        require(maxFlashesPerWindow in 1..3) {
            "the 3 Hz ceiling is not configurable upward"
        }
    }

    companion object {
        val DEFAULT = FlashPolicy()

        /** Screen-only, no transition faster than 1 Hz. Reachable in one tap. */
        val REDUCED = FlashPolicy(maxFlashesPerWindow = 1, targetRateHz = 1f)
    }
}

data class LimiterReport(
    val originalFlashCount: Int,
    val finalFlashCount: Int,
    val droppedCount: Int,
    val originalPeakRateHz: Float,
    val finalPeakRateHz: Float,
    /** Longest continuous stretch at the ceiling. Advisory only. */
    val sustainedAtCapMs: Int = 0,
    val recommendEscalation: Boolean = false,
) {
    val changedAnything: Boolean get() = droppedCount > 0

    /** Shown verbatim on the show screen — designers should see the number. */
    fun summary(): String = when {
        recommendEscalation ->
            "Peak rate was ${fmt(originalPeakRateHz)}/sec. Auto-set to a wider spacing — " +
                "flashing every beat exceeds the safe rate at this tempo."
        changedAnything ->
            "Trimmed $droppedCount flash${if (droppedCount == 1) "" else "es"} to stay at " +
                "${fmt(finalPeakRateHz)}/sec."
        else -> "Peak ${fmt(finalPeakRateHz)} flashes/sec — within the safe limit."
    }

    private fun fmt(v: Float) = ((v * 10).roundToInt() / 10.0).toString().removeSuffix(".0")
}
