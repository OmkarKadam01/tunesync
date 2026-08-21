package com.tunesync.core.output

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

internal object VibratorProbe {
    /** Returns (hasVibrator, hasAmplitudeControl). */
    fun probe(context: Context): Pair<Boolean, Boolean> {
        val v = obtain(context) ?: return false to false
        return v.hasVibrator() to v.hasAmplitudeControl()
    }

    fun obtain(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}

/**
 * Downbeat haptics. Never the primary output — it is invisible to the crowd and
 * costs battery — but it is the accessible channel for anyone who opts out of
 * flashing entirely.
 */
class HapticDriver(context: Context) {

    private val vibrator = VibratorProbe.obtain(context)
    private val hasAmplitude = vibrator?.hasAmplitudeControl() == true

    val isAvailable: Boolean get() = vibrator?.hasVibrator() == true

    fun pulse(durationMs: Int, strength: Float) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (hasAmplitude) {
            val amplitude = (strength.coerceIn(0.05f, 1f) * 255).roundToInt().coerceIn(1, 255)
            VibrationEffect.createOneShot(durationMs.toLong(), amplitude)
        } else {
            VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { v.vibrate(effect) }
    }

    fun cancel() {
        runCatching { vibrator?.cancel() }
    }
}
