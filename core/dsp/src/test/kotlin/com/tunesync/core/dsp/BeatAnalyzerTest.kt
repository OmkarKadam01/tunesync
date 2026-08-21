package com.tunesync.core.dsp

import com.tunesync.core.model.BeatMap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeatAnalyzerTest {

    /** Standard MIR tolerance: a beat within 70 ms of ground truth is a hit. */
    private val toleranceMs = 70

    private fun analyze(track: SyntheticAudio.Track): BeatMap {
        val result = BeatAnalyzer.analyze(track.signal, "test")
        assertTrue(result is AnalysisResult.Success, "expected success, got $result")
        return (result as AnalysisResult.Success).map
    }

    /** One-to-one greedy matching, then F-measure. */
    private fun fMeasure(detected: List<Int>, truth: List<Int>): Float {
        if (detected.isEmpty() || truth.isEmpty()) return 0f
        val used = BooleanArray(truth.size)
        var hits = 0
        for (d in detected) {
            var bestIdx = -1
            var bestErr = toleranceMs + 1
            for (i in truth.indices) {
                if (used[i]) continue
                val err = abs(d - truth[i])
                if (err <= toleranceMs && err < bestErr) {
                    bestErr = err
                    bestIdx = i
                }
            }
            if (bestIdx >= 0) {
                used[bestIdx] = true
                hits++
            }
        }
        val precision = hits.toFloat() / detected.size
        val recall = hits.toFloat() / truth.size
        return if (precision + recall == 0f) 0f else 2 * precision * recall / (precision + recall)
    }

    @Test
    fun `tempo is recovered across the common range`() {
        for (bpm in floatArrayOf(70f, 90f, 100f, 110f, 120f, 128f, 140f, 150f, 174f)) {
            val track = SyntheticAudio.drumPattern(bpm, bars = 16)
            val map = analyze(track)
            val err = abs(map.analysis.tempoBpm - bpm)
            assertTrue(
                err < bpm * 0.03f,
                "at $bpm bpm the analyser said ${map.analysis.tempoBpm} (error $err)",
            )
        }
    }

    @Test
    fun `beat grid meets the F-measure bar`() {
        for (bpm in floatArrayOf(90f, 120f, 128f, 140f)) {
            val track = SyntheticAudio.drumPattern(bpm, bars = 16)
            val map = analyze(track)
            val f = fMeasure(map.beats.map { it.tMs }, track.beatTimesMs)
            assertTrue(f >= 0.90f, "F1 at $bpm bpm was $f, need >= 0.90")
        }
    }

    @Test
    fun `a sustained vocal does not derail the grid`() {
        val bpm = 120f
        val dry = analyze(SyntheticAudio.drumPattern(bpm, bars = 16, withVocal = false))
        val wet = analyze(SyntheticAudio.drumPattern(bpm, bars = 16, withVocal = true))

        val truth = SyntheticAudio.drumPattern(bpm, bars = 16).beatTimesMs
        val fWet = fMeasure(wet.beats.map { it.tMs }, truth)
        assertTrue(fWet >= 0.90f, "F1 with a vocal present was $fWet")
        assertTrue(
            abs(wet.analysis.tempoBpm - dry.analysis.tempoBpm) < 1f,
            "the vocal shifted the tempo estimate from ${dry.analysis.tempoBpm} to ${wet.analysis.tempoBpm}",
        )
    }

    @Test
    fun `moderate noise is tolerated`() {
        val track = SyntheticAudio.drumPattern(128f, bars = 16, withNoise = 0.05f)
        val map = analyze(track)
        val f = fMeasure(map.beats.map { it.tMs }, track.beatTimesMs)
        assertTrue(f >= 0.85f, "F1 with noise was $f")
    }

    @Test
    fun `downbeats land on bar one`() {
        val track = SyntheticAudio.drumPattern(120f, bars = 16)
        val map = analyze(track)
        assertEquals(4, map.analysis.meterNumerator)

        val detectedDownbeats = map.beats.filter { it.isDownbeat }.map { it.tMs }
        assertTrue(detectedDownbeats.size >= 12, "found only ${detectedDownbeats.size} downbeats")
        val f = fMeasure(detectedDownbeats, track.downbeatTimesMs)
        assertTrue(f >= 0.85f, "downbeat F1 was $f")
    }

    @Test
    fun `downbeats score higher than passing beats`() {
        val map = analyze(SyntheticAudio.drumPattern(120f, bars = 16))
        val downbeat = map.beats.filter { it.isDownbeat }.map { it.salience }.average()
        val other = map.beats.filter { !it.isDownbeat }.map { it.salience }.average()
        assertTrue(downbeat > other, "downbeat salience $downbeat should exceed $other")
    }

    @Test
    fun `salience spans a usable range`() {
        val map = analyze(SyntheticAudio.drumPattern(120f, bars = 16))
        val min = map.beats.minOf { it.salience }
        val max = map.beats.maxOf { it.salience }
        assertTrue(max - min > 0.25f, "salience range $min..$max is too flat to threshold on")
        assertTrue(map.beats.all { it.salience in 0f..1f }, "salience must stay in 0..1")
    }

    @Test
    fun `silence is refused`() {
        val result = BeatAnalyzer.analyze(SyntheticAudio.silence(), "silent")
        assertTrue(result is AnalysisResult.Silent, "expected Silent, got $result")
    }

    @Test
    fun `a pitched drone with no transients is refused rather than guessed at`() {
        val result = BeatAnalyzer.analyze(SyntheticAudio.sustainedTone(), "drone")
        assertTrue(
            result is AnalysisResult.NoBeat,
            "expected an honest refusal on a beatless drone, got $result",
        )
    }

    @Test
    fun `confidence is high on a clean pattern`() {
        val map = analyze(SyntheticAudio.drumPattern(120f, bars = 16))
        assertTrue(
            map.analysis.confidence > 0.6f,
            "confidence on a clean drum machine pattern was only ${map.analysis.confidence}",
        )
    }

    @Test
    fun `halving and doubling the grid preserves alignment`() {
        val track = SyntheticAudio.drumPattern(120f, bars = 16)
        val map = analyze(track)

        val halved = BeatAnalyzer.rescale(map, 0.5f)
        assertTrue(abs(halved.analysis.tempoBpm - 60f) < 3f, "half-time should be ~60 bpm")
        assertEquals(halved.beats.size, halved.beats.map { it.idx }.distinct().size)

        val doubled = BeatAnalyzer.rescale(map, 2f)
        assertTrue(abs(doubled.analysis.tempoBpm - 240f) < 6f, "double-time should be ~240 bpm")
        assertTrue(doubled.beats.size > map.beats.size)
        assertTrue(
            doubled.beats.zipWithNext().all { (a, b) -> a.tMs <= b.tMs },
            "doubled grid must stay sorted",
        )
    }

    @Test
    fun `edits layer over beats without mutating them`() {
        val map = analyze(SyntheticAudio.drumPattern(120f, bars = 8))
        val edited = map.copy(
            edits = map.edits.copy(globalOffsetMs = -20, muted = listOf(0, 1)),
        )
        val effective = edited.effectiveBeats()
        assertEquals(map.beats.size - 2, effective.size)
        assertEquals(map.beats[2].tMs - 20, effective.first().tMs)
        assertEquals(map.beats.size, edited.beats.size, "the underlying beats must be untouched")
    }

    @Test
    fun `analysis of a four minute track stays inside the time budget`() {
        // 4 minutes at 128 bpm is 128 bars.
        val track = SyntheticAudio.drumPattern(128f, bars = 128)
        val seconds = track.signal.samples.size / SyntheticAudio.RATE
        assertTrue(seconds >= 230, "fixture should be about four minutes, was $seconds s")

        val result = BeatAnalyzer.analyze(track.signal, "long")
        assertTrue(result is AnalysisResult.Success)
        val elapsed = (result as AnalysisResult.Success).elapsedMs
        println("4-minute analysis took ${elapsed} ms on this host")
        // Host budget only. The 8 s target in the PRD is for a mid-range phone and
        // is verified on device; a host that cannot beat 20 s will never make it.
        assertTrue(elapsed < 20_000, "analysis took ${elapsed} ms, far outside budget")
    }
}
