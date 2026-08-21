package com.tunesync.core.dsp

/**
 * Where in the reference track the query audio is, expressed as a frame offset.
 *
 * `deltaFrames` is `referenceFrame - queryFrame`: add it to a query frame to get
 * the position in the track.
 */
data class MatchResult(
    val deltaFrames: Int,
    /** Query hashes agreeing on this offset. The primary strength signal. */
    val votes: Int,
    val totalMatches: Int,
    /** 0..1 from the margin over the next-best offset. */
    val confidence: Float,
) {
    fun positionMsAtQueryStart(hopMs: Float): Float = deltaFrames * hopMs
}

/**
 * The reference fingerprint for one track, built once at import.
 *
 * Held as a sorted primitive array rather than a `Map<Int, List<Int>>`: a
 * four-minute track produces tens of thousands of hashes, and the boxing alone
 * would cost more than the search.
 */
class FingerprintIndex(private val entries: LongArray, val hopMs: Float) {

    val size: Int get() = entries.size

    /** Approximate heap cost, for deciding how many tracks can stay resident. */
    val approximateBytes: Int get() = entries.size * 8

    /**
     * Align [queryHashes] against this index.
     *
     * @param expectedDelta when the caller already has a lock, offsets further
     *   than [tolerance] from it are ignored. That turns a full search into a
     *   confirmation and is what makes a twice-a-second re-lock affordable.
     */
    fun match(
        queryHashes: LongArray,
        expectedDelta: Int? = null,
        tolerance: Int = Int.MAX_VALUE,
    ): MatchResult? {
        if (queryHashes.isEmpty() || entries.isEmpty()) return null

        // Histogram of candidate offsets. A correct alignment makes every matching
        // pair agree on the same delta, so the true offset appears as a spike while
        // coincidental hash collisions scatter uniformly.
        val votes = HashMap<Int, Int>(queryHashes.size)
        var totalMatches = 0

        for (q in queryHashes) {
            val hash = Fingerprinter.hashOf(q)
            val qFrame = Fingerprinter.frameOf(q)
            var i = lowerBound(hash)
            while (i < entries.size && Fingerprinter.hashOf(entries[i]) == hash) {
                val delta = Fingerprinter.frameOf(entries[i]) - qFrame
                i++
                if (expectedDelta != null && kotlin.math.abs(delta - expectedDelta) > tolerance) continue
                votes[delta] = (votes[delta] ?: 0) + 1
                totalMatches++
            }
        }
        if (votes.isEmpty()) return null

        var best = 0
        var bestVotes = 0
        var secondVotes = 0
        for ((delta, count) in votes) {
            if (count > bestVotes) {
                secondVotes = bestVotes
                bestVotes = count
                best = delta
            } else if (count > secondVotes) {
                secondVotes = count
            }
        }

        // Neighbouring frames often split the vote for the same true alignment, so
        // fold them in before judging strength.
        val merged = bestVotes + (votes[best - 1] ?: 0) + (votes[best + 1] ?: 0)
        val rival = votes.entries
            .filter { kotlin.math.abs(it.key - best) > 2 }
            .maxOfOrNull { it.value } ?: 0

        val confidence = if (merged <= 0) 0f else ((merged - rival).toFloat() / merged).coerceIn(0f, 1f)
        return MatchResult(best, merged, totalMatches, confidence)
    }

    /** First index whose hash is >= [hash]. */
    private fun lowerBound(hash: Int): Int {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (Fingerprinter.hashOf(entries[mid]) < hash) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        fun build(spec: Spectrogram): FingerprintIndex =
            FingerprintIndex(Fingerprinter.hashes(spec), spec.hopMs)

        fun of(signal: AudioSignal): FingerprintIndex =
            build(Stft.compute(signal.resampleTo(AudioSignal.ANALYSIS_RATE)))
    }
}
