package com.tunesync.core.output

/**
 * Measured hardware behaviour, written once by the capability probe and read by
 * the show compiler and the scheduler.
 *
 * Torch behaviour varies more between Android devices than almost anything else
 * on the platform, and none of it is discoverable from the API.
 */
data class DeviceProfile(
    val hasTorch: Boolean = false,
    val cameraId: String? = null,
    /**
     * Round-trip time of a torch call. This is the *software* latency; true
     * optical latency needs the photodiode rig and is typically a few ms more.
     */
    val torchLatencyMs: Float = DEFAULT_TORCH_LATENCY_MS,
    /** Fastest rate at which toggles were still honoured, in Hz. */
    val torchMaxRateHz: Float = 10f,
    /** 1 means on/off only; more means graded brightness is available. */
    val torchStrengthLevels: Int = 1,
    val hasVibrator: Boolean = false,
    val hasAmplitudeControl: Boolean = false,
    val probedAtSdk: Int = 0,
) {
    val supportsGradedTorch: Boolean get() = torchStrengthLevels > 1

    /** Torch cues are scheduled this much early so the light lands on the beat. */
    val compensationMs: Float get() = torchLatencyMs

    companion object {
        /**
         * Used until the probe runs. Chosen from the middle of the observed range
         * rather than optimistically: a device that is slower than assumed reads as
         * sloppy, while one that is faster reads as merely early by a few ms.
         */
        const val DEFAULT_TORCH_LATENCY_MS = 18f

        val UNKNOWN = DeviceProfile()
    }
}
