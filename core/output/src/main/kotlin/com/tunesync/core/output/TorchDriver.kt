package com.tunesync.core.output

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Torch control through Camera2.
 *
 * Notably this needs no CAMERA permission: no camera device is ever opened, only
 * the flash unit is addressed. Keeping it that way is worth more to onboarding
 * than any feature that would require the permission.
 */
class TorchDriver(context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val appContext = context.applicationContext

    @Volatile
    var profile: DeviceProfile = DeviceProfile.UNKNOWN
        private set

    @Volatile
    private var available = true

    @Volatile
    private var isOn = false

    private val stolen = AtomicBoolean(false)

    /** Rolling duty accounting, to stay clear of LED driver current limits. */
    private var windowStartNanos = 0L
    private var onNanosInWindow = 0L
    private var lastOnNanos = 0L

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != profile.cameraId) return
            // Another app or the quick-settings tile moved the torch. Yield rather
            // than fight it — a toggle war with the system UI is unwinnable and
            // looks like a malfunction.
            if (enabled != isOn) stolen.set(true)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == profile.cameraId) available = false
        }
    }

    fun start() {
        profile = probe()
        if (profile.hasTorch) manager.registerTorchCallback(torchCallback, null)
    }

    fun stop() {
        runCatching { manager.unregisterTorchCallback(torchCallback) }
        off()
    }

    /** True when the torch was taken by something else since the last check. */
    fun consumeStolenFlag(): Boolean = stolen.getAndSet(false)

    val isAvailable: Boolean get() = available && profile.hasTorch

    /**
     * @param strength 0..1, mapped to a hardware level where the device supports
     *   graded brightness and ignored otherwise.
     */
    fun on(strength: Float = 1f) {
        val id = profile.cameraId ?: return
        if (!available) return
        if (isDutyExceeded()) return
        try {
            if (profile.supportsGradedTorch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val level = (strength.coerceIn(0.05f, 1f) * profile.torchStrengthLevels)
                    .roundToInt().coerceIn(1, profile.torchStrengthLevels)
                manager.turnOnTorchWithStrengthLevel(id, level)
            } else {
                manager.setTorchMode(id, true)
            }
            if (!isOn) lastOnNanos = SystemClock.elapsedRealtimeNanos()
            isOn = true
        } catch (e: CameraAccessException) {
            // CAMERA_IN_USE and friends: another app holds the camera. Give up the
            // channel for now; the show keeps running on screen and haptics.
            available = false
            Log.w(TAG, "torch unavailable: ${e.reason}")
        } catch (e: IllegalArgumentException) {
            available = false
            Log.w(TAG, "torch rejected", e)
        }
    }

    fun off() {
        val id = profile.cameraId ?: return
        try {
            manager.setTorchMode(id, false)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "failed to turn torch off: ${e.reason}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "failed to turn torch off", e)
        }
        if (isOn) {
            onNanosInWindow += SystemClock.elapsedRealtimeNanos() - lastOnNanos
            isOn = false
        }
    }

    /** Lets the driver recover after the camera is released by whoever took it. */
    fun retryAvailability() {
        if (!available && profile.hasTorch) available = true
    }

    /**
     * Beyond this the LED driver starts current-limiting or cutting out, and the
     * phone gets hot enough that the user notices.
     */
    private fun isDutyExceeded(): Boolean {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - windowStartNanos > DUTY_WINDOW_NANOS) {
            windowStartNanos = now
            onNanosInWindow = 0
            return false
        }
        return onNanosInWindow > (DUTY_WINDOW_NANOS * MAX_DUTY).toLong()
    }

    private fun probe(): DeviceProfile {
        val vibratorInfo = VibratorProbe.probe(appContext)
        return try {
            var found: String? = null
            var levels = 1
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                // Prefer the rear flash, but take a front one rather than nothing.
                if (found == null || facing == CameraCharacteristics.LENS_FACING_BACK) {
                    found = id
                    levels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                    } else {
                        1
                    }
                }
                if (facing == CameraCharacteristics.LENS_FACING_BACK) break
            }
            DeviceProfile(
                hasTorch = found != null,
                cameraId = found,
                torchStrengthLevels = levels,
                hasVibrator = vibratorInfo.first,
                hasAmplitudeControl = vibratorInfo.second,
                probedAtSdk = Build.VERSION.SDK_INT,
            )
        } catch (e: CameraAccessException) {
            Log.w(TAG, "torch probe failed", e)
            DeviceProfile(hasVibrator = vibratorInfo.first, hasAmplitudeControl = vibratorInfo.second)
        }
    }

    /**
     * Times the torch call round trip.
     *
     * This measures software latency only. True optical latency — the number that
     * actually matters — needs a photodiode on the LED, so this is a floor, not
     * the answer. Runs off the scheduler thread and takes about a second.
     */
    fun measureLatency(samples: Int = 6): DeviceProfile {
        if (!isAvailable) return profile
        var total = 0L
        var counted = 0
        repeat(samples) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            on(0.3f)
            val t1 = SystemClock.elapsedRealtimeNanos()
            off()
            if (isOn || t1 > t0) {
                total += t1 - t0
                counted++
            }
            Thread.sleep(80)
        }
        if (counted == 0) return profile
        val measuredMs = total / counted / 1_000_000f
        profile = profile.copy(
            torchLatencyMs = (measuredMs + OPTICAL_ALLOWANCE_MS).coerceIn(2f, 60f),
        )
        return profile
    }

    private companion object {
        const val TAG = "TorchDriver"
        const val MAX_DUTY = 0.35f
        const val DUTY_WINDOW_NANOS = 10_000_000_000L

        /** LED rise time plus HAL queueing that the call timing cannot see. */
        const val OPTICAL_ALLOWANCE_MS = 6f
    }
}
