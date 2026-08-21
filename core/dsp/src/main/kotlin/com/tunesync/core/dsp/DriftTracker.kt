package com.tunesync.core.dsp

import kotlin.math.abs

enum class LockState {
    /** No usable alignment yet. */
    SEARCHING,

    /** Recent measurements agree; position is trustworthy. */
    LOCKED,

    /** No recent measurement, running on prediction. Still usable, briefly. */
    COASTING,

    /** Coasted too long. Output should fade rather than guess. */
    LOST,
}

/**
 * Turns intermittent, noisy match results into a continuous playback position.
 *
 * Fits position against wall clock over a sliding window, which recovers both
 * the offset and the *rate*. Rate matters for two reasons: the venue's playback
 * clock and the phone's crystal differ by 10–50 ppm, and a DJ may be running
 * the track off-speed entirely. Snapping position to each new measurement
 * instead would make the show twitch on every update.
 */
class DriftTracker(
    private val maxMeasurements: Int = 24,
    /** A measurement disagreeing with prediction by more than this is suspect. */
    private val outlierToleranceMs: Float = 250f,
    /** Consecutive rejections before we assume the world moved and re-seek. */
    private val rejectionsBeforeReset: Int = 4,
    /** How long prediction stays trustworthy without a fresh measurement. */
    private val coastLimitMs: Long = 8_000,
    private val lockAfterMeasurements: Int = 2,
) {
    private val wallMs = FloatArray(maxMeasurements)
    private val trackMs = FloatArray(maxMeasurements)
    private val weight = FloatArray(maxMeasurements)
    private var count = 0
    private var head = 0

    // Explicit flags rather than 0 sentinels: a monotonic clock reading of zero is
    // legitimate, and treating it as "unset" re-anchors the origin on every call,
    // which silently destroys the fit.
    private var originNanos = 0L
    private var hasOrigin = false
    private var lastAcceptedNanos = 0L
    private var hasAccepted = false
    private var consecutiveRejections = 0

    /** Track ms per wall ms. 1.0 is nominal; a DJ pitch shift moves it. */
    var rate: Float = 1f
        private set

    private var offset: Float = 0f
    private var fitted = false

    val measurements: Int get() = count

    fun stateAt(nowNanos: Long): LockState = when {
        !fitted || count < lockAfterMeasurements -> LockState.SEARCHING
        elapsedSinceAccepted(nowNanos) > coastLimitMs -> LockState.LOST
        elapsedSinceAccepted(nowNanos) > STALE_MS -> LockState.COASTING
        else -> LockState.LOCKED
    }

    private fun elapsedSinceAccepted(nowNanos: Long): Long =
        if (!hasAccepted) Long.MAX_VALUE else (nowNanos - lastAcceptedNanos) / 1_000_000

    /**
     * Offer a measurement. Returns false when it was rejected as an outlier.
     *
     * Rejection is not the same as ignoring: a run of rejections means the
     * prediction itself is wrong — the track was skipped, restarted or changed —
     * so the fit is discarded and the next measurement is taken at face value.
     */
    fun add(wallNanos: Long, positionMs: Float, confidence: Float): Boolean {
        if (confidence <= 0f) return false
        if (!hasOrigin) {
            originNanos = wallNanos
            hasOrigin = true
        }

        if (fitted && count >= lockAfterMeasurements) {
            val predicted = predict(wallNanos)
            if (abs(positionMs - predicted) > outlierToleranceMs) {
                consecutiveRejections++
                if (consecutiveRejections >= rejectionsBeforeReset) reset()
                return false
            }
        }
        consecutiveRejections = 0

        val w = (wallNanos - originNanos) / 1_000_000f
        wallMs[head] = w
        trackMs[head] = positionMs
        weight[head] = confidence.coerceIn(0.05f, 1f)
        head = (head + 1) % maxMeasurements
        if (count < maxMeasurements) count++
        lastAcceptedNanos = wallNanos
        hasAccepted = true

        refit()
        return true
    }

    /** Predicted track position, or null when there is nothing to predict from. */
    fun positionAt(nowNanos: Long): Float? {
        if (!fitted) return null
        return predict(nowNanos)
    }

    private fun predict(nowNanos: Long): Float {
        val w = (nowNanos - originNanos) / 1_000_000f
        return offset + rate * w
    }

    /**
     * Confidence-weighted least squares over the window.
     *
     * With a single measurement there is nothing to fit a slope to, so rate is
     * pinned at nominal until a second one arrives.
     */
    private fun refit() {
        if (count == 1) {
            offset = trackMs[0] - wallMs[0]
            rate = 1f
            fitted = true
            return
        }

        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until count) {
            val wgt = weight[i].toDouble()
            val x = wallMs[i].toDouble()
            val y = trackMs[i].toDouble()
            sw += wgt
            sx += wgt * x
            sy += wgt * y
            sxx += wgt * x * x
            sxy += wgt * x * y
        }
        val denom = sw * sxx - sx * sx
        if (abs(denom) < 1e-6) {
            offset = (sy / sw).toFloat() - (sx / sw).toFloat()
            rate = 1f
            fitted = true
            return
        }
        val slope = (sw * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / sw

        // Beyond this the "track" is not the recording we fingerprinted, and a
        // runaway slope would send the show sprinting off the end.
        rate = slope.toFloat().coerceIn(MIN_RATE, MAX_RATE)
        offset = intercept.toFloat()
        fitted = true
    }

    fun reset() {
        count = 0
        head = 0
        fitted = false
        rate = 1f
        offset = 0f
        originNanos = 0L
        hasOrigin = false
        lastAcceptedNanos = 0L
        hasAccepted = false
        consecutiveRejections = 0
    }

    private companion object {
        /** Older than this and we call it coasting rather than locked. */
        const val STALE_MS = 1_500L

        /** ±8%: covers crystal drift and a DJ's pitch fader, not a different song. */
        const val MIN_RATE = 0.92f
        const val MAX_RATE = 1.08f
    }
}
