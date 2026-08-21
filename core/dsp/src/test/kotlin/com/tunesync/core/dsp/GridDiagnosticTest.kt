package com.tunesync.core.dsp

import kotlin.math.abs
import kotlin.test.Test

/** Printout for diagnosing grid alignment, not an assertion suite. */
class GridDiagnosticTest {

    @Test
    fun `print grid alignment against ground truth`() {
        data class Case(val name: String, val bpm: Float, val vocal: Boolean)
        val cases = listOf(
            Case("120 clean", 120f, false),
            Case("128 clean", 128f, false),
            Case("120 + vocal", 120f, true),
        )

        for (c in cases) {
            val track = SyntheticAudio.drumPattern(c.bpm, bars = 16, withVocal = c.vocal)
            val result = BeatAnalyzer.analyze(track.signal, c.name)
            if (result !is AnalysisResult.Success) {
                println("=== ${c.name}: $result")
                continue
            }
            val map = result.map
            val detected = map.beats.map { it.tMs }
            val truth = track.beatTimesMs

            // Signed error of each detection to its nearest truth beat.
            val errors = detected.map { d -> truth.minByOrNull { abs(it - d) }!!.let { d - it } }
            val mean = errors.average()
            val drift = if (errors.size > 4) errors.takeLast(4).average() - errors.take(4).average() else 0.0

            println(
                "=== ${c.name}: detected %.2f bpm (truth %.0f), conf %.2f, %d beats (truth %d)"
                    .format(map.analysis.tempoBpm, c.bpm, map.analysis.confidence, detected.size, truth.size),
            )
            println(
                "    mean signed error %.1f ms, |err| %.1f ms, start-to-end drift %.1f ms, within 70ms: %d"
                    .format(mean, errors.map { abs(it) }.average(), drift, errors.count { abs(it) <= 70 }),
            )
            println("    first detected: ${detected.take(6)}")
            println("    first truth:    ${truth.take(6)}")
        }
    }
}
