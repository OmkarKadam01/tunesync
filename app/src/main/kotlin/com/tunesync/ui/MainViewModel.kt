package com.tunesync.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tunesync.audio.AudioDecoder
import com.tunesync.audio.DecodeResult
import com.tunesync.audio.TrackPlayer
import com.tunesync.audio.WaveformPeaks
import com.tunesync.core.dsp.AnalysisResult
import com.tunesync.core.dsp.AnalysisStage
import com.tunesync.core.dsp.BeatAnalyzer
import com.tunesync.core.dsp.FingerprintIndex
import com.tunesync.core.listen.AlignStatus
import com.tunesync.core.listen.LiveAligner
import com.tunesync.core.listen.MicCapture
import com.tunesync.service.ShowService
import com.tunesync.service.ShowSession
import com.tunesync.core.model.BeatMap
import com.tunesync.core.model.CompileOptions
import com.tunesync.core.model.Cue
import com.tunesync.core.model.ShowCompiler
import com.tunesync.core.model.ShowStyle
import com.tunesync.core.output.HapticDriver
import com.tunesync.core.output.ShowRunner
import com.tunesync.core.output.TorchDriver
import com.tunesync.core.safety.ArmedShow
import com.tunesync.core.safety.FlashLimiter
import com.tunesync.core.safety.FlashPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which outputs the show is allowed to drive. Independent switches rather than
 * one "reduced" flag, because a user who wants the torch off in a dark room and
 * a user who wants the screen off to save battery want different things.
 */
data class OutputSettings(
    val torch: Boolean = true,
    val screen: Boolean = true,
    /** No strobing on any channel: 1 Hz ceiling and smooth ramps only. */
    val gentle: Boolean = false,
) {
    val anyEnabled: Boolean get() = torch || screen
}

/** Rehearsing against local playback, or following a live source in the room. */
enum class ShowMode { REHEARSE, LISTEN }

sealed interface UiState {
    data object Empty : UiState

    data class Working(val stage: AnalysisStage, val progress: Float) : UiState

    data class Loaded(
        val map: BeatMap,
        val peaks: WaveformPeaks,
        val style: ShowStyle,
        val show: ArmedShow,
        val output: OutputSettings,
        val torchAvailable: Boolean,
    ) : UiState

    data class Failed(val message: String, val canRetry: Boolean = true) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val torch = TorchDriver(app)
    private val haptics = HapticDriver(app)

    private val _state = MutableStateFlow<UiState>(UiState.Empty)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _screenCue = MutableStateFlow<Cue?>(null)
    val screenCue: StateFlow<Cue?> = _screenCue.asStateFlow()

    private val _torchFlash = MutableStateFlow(0L)
    val torchFlash: StateFlow<Long> = _torchFlash.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Non-fatal playback problem, shown beside the transport rather than replacing the map. */
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    /** Consent is recorded once and re-asked only if the limiter changes. */
    private val _needsConsent = MutableStateFlow(true)
    val needsConsent: StateFlow<Boolean> = _needsConsent.asStateFlow()

    /** True playback position, sampled from the audio clock rather than a timer. */
    private val _positionMs = MutableStateFlow(0f)
    val positionMs: StateFlow<Float> = _positionMs.asStateFlow()

    /** Set when the device cannot report a real DAC timestamp, so timing is degraded. */
    private val _degradedClock = MutableStateFlow(false)
    val degradedClock: StateFlow<Boolean> = _degradedClock.asStateFlow()

    private val _mode = MutableStateFlow(ShowMode.REHEARSE)
    val mode: StateFlow<ShowMode> = _mode.asStateFlow()

    private val _alignStatus = MutableStateFlow(AlignStatus())
    val alignStatus: StateFlow<AlignStatus> = _alignStatus.asStateFlow()

    private val runner = ShowRunner(torch, haptics) { cue -> _screenCue.value = cue }
    private var player: TrackPlayer? = null
    private var fingerprint: FingerprintIndex? = null
    private var aligner: LiveAligner? = null
    private var job: Job? = null
    private var positionJob: Job? = null
    private var alignJob: Job? = null

    init {
        torch.start()
        // The notification's stop action has to reach whatever is actually running.
        ShowSession.register { viewModelScope.launch { stopShow() } }
        runner.onTorchFired = { _torchFlash.value = System.currentTimeMillis() }
        runner.onFinished = {
            _running.value = false
            _screenCue.value = null
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _needsConsent.value = !prefs.getBoolean(KEY_CONSENT, false)
    }

    fun grantConsent(reducedFlash: Boolean) {
        getApplication<Application>()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONSENT, true)
            .putLong(KEY_CONSENT_AT, System.currentTimeMillis())
            .apply()
        _needsConsent.value = false
        // The safe choice lands on the visible switches, so the user can see what
        // it did and undo it.
        if (reducedFlash) updateOutput { it.copy(torch = false, gentle = true) }
    }

    fun importTrack(uri: Uri) {
        job?.cancel()
        stopShow()
        job = viewModelScope.launch {
            _state.value = UiState.Working(AnalysisStage.DECODING, 0f)
            val app = getApplication<Application>()

            val decoded = withContext(Dispatchers.Default) {
                AudioDecoder.decode(
                    app,
                    uri,
                    onProgress = { p ->
                        _state.value = UiState.Working(AnalysisStage.DECODING, p * 0.35f)
                    },
                    isCancelled = { !isActiveJob() },
                )
            }

            val (sig, trackId, peaks) = when (decoded) {
                is DecodeResult.Success -> Triple(decoded.signal, decoded.trackId, decoded.peaks)
                is DecodeResult.Truncated -> Triple(decoded.signal, decoded.trackId, decoded.peaks)
                DecodeResult.Protected -> {
                    _state.value = UiState.Failed(app.getString(com.tunesync.R.string.err_drm), false)
                    return@launch
                }
                is DecodeResult.Unsupported -> {
                    _state.value = UiState.Failed(app.getString(com.tunesync.R.string.err_unsupported), false)
                    return@launch
                }
                DecodeResult.NotReadable -> {
                    _state.value = UiState.Failed(app.getString(com.tunesync.R.string.err_read))
                    return@launch
                }
            }

            val analysis = withContext(Dispatchers.Default) {
                BeatAnalyzer.analyze(sig, trackId) { stage, p ->
                    _state.value = UiState.Working(stage, 0.35f + p * 0.65f)
                }
            }

            when (analysis) {
                is AnalysisResult.Success -> {
                    player = TrackPlayer(sig)
                    fingerprint = analysis.fingerprint
                    loadMap(analysis.map, peaks, ShowStyle.PULSE, restoreOutput())
                }
                is AnalysisResult.NoBeat ->
                    _state.value = UiState.Failed(analysis.reason)
                is AnalysisResult.Silent ->
                    _state.value = UiState.Failed(app.getString(com.tunesync.R.string.err_silent), false)
            }
        }
    }

    private fun isActiveJob() = job?.isActive != false

    fun setStyle(style: ShowStyle) {
        val current = _state.value as? UiState.Loaded ?: return
        loadMap(current.map, current.peaks, style, current.output)
    }

    fun setTorchEnabled(enabled: Boolean) = updateOutput { it.copy(torch = enabled) }

    fun setScreenEnabled(enabled: Boolean) = updateOutput { it.copy(screen = enabled) }

    fun setGentle(enabled: Boolean) = updateOutput { it.copy(gentle = enabled) }

    private fun updateOutput(transform: (OutputSettings) -> OutputSettings) {
        val current = _state.value as? UiState.Loaded ?: return
        val next = transform(current.output)
        persist(next)
        // Changing a channel mid-show would leave the runner holding a stale cue
        // list, so stop rather than swap underneath it.
        if (_running.value) stopShow()
        loadMap(current.map, current.peaks, current.style, next)
    }

    private fun loadMap(
        map: BeatMap,
        peaks: WaveformPeaks,
        style: ShowStyle,
        output: OutputSettings,
    ) {
        val options = CompileOptions(
            latencyCompensationMs = torch.profile.compensationMs,
            enableTorch = output.torch && torch.isAvailable,
            enableScreen = output.screen,
            enableHaptics = false,
            forceSmooth = output.gentle,
        )
        val compiled = ShowCompiler.compile(map, style, options)
        val policy = if (output.gentle) FlashPolicy.REDUCED else FlashPolicy.DEFAULT
        val armed = FlashLimiter.arm(compiled, policy)

        _state.value = UiState.Loaded(
            map = map,
            peaks = peaks,
            style = style,
            show = armed,
            output = output,
            torchAvailable = torch.isAvailable,
        )
    }

    private fun persist(output: OutputSettings) {
        getApplication<Application>()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TORCH, output.torch)
            .putBoolean(KEY_SCREEN, output.screen)
            .putBoolean(KEY_GENTLE, output.gentle)
            .apply()
    }

    private fun restoreOutput(): OutputSettings {
        val prefs = getApplication<Application>()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return OutputSettings(
            torch = prefs.getBoolean(KEY_TORCH, true),
            screen = prefs.getBoolean(KEY_SCREEN, true),
            gentle = prefs.getBoolean(KEY_GENTLE, false),
        )
    }

    fun startShow() {
        val loaded = _state.value as? UiState.Loaded ?: return
        val p = player ?: return
        _playbackError.value = null
        try {
            p.start(0)
            runner.start(loaded.show, p)
            _running.value = true
            ShowService.start(getApplication(), listening = false, title = "TuneSync")
            startPositionUpdates { p.positionMsAt(it) }
        } catch (e: Exception) {
            // Audio device setup can fail for reasons outside our control — a route
            // change, an exclusive-mode holder, an OEM codec quirk. The show simply
            // does not start; it must not take the process down.
            runCatching { p.stop() }
            runCatching { runner.stop() }
            _running.value = false
            _playbackError.value = "Couldn't start audio playback on this device."
        }
    }

    /**
     * Enter or leave listening mode. Switching stops whatever is running, since
     * the two modes drive the scheduler from different clocks.
     */
    fun setMode(next: ShowMode) {
        if (_mode.value == next) return
        stopShow()
        _mode.value = next
        _alignStatus.value = AlignStatus()
    }

    /**
     * Follow a live source playing this track.
     *
     * Nothing is played locally: position comes from the microphone by way of
     * fingerprint alignment, and the scheduler cannot tell the difference.
     */
    fun startListening() {
        val loaded = _state.value as? UiState.Loaded ?: return
        val index = fingerprint ?: return
        _playbackError.value = null

        val live = LiveAligner(index, loaded.map.durationMs)
        val capture = MicCapture(getApplication())
        if (!live.start(capture)) {
            _alignStatus.value = live.status.value
            _playbackError.value = live.status.value.error ?: "Couldn't start the microphone."
            return
        }
        aligner = live

        alignJob?.cancel()
        alignJob = viewModelScope.launch {
            live.status.collect { _alignStatus.value = it }
        }

        try {
            runner.start(loaded.show, live)
            _running.value = true
            ShowService.start(getApplication(), listening = true, title = "TuneSync")
            startPositionUpdates { live.positionMsAt(it) }
        } catch (e: Exception) {
            live.stop()
            aligner = null
            _running.value = false
            _playbackError.value = "Couldn't start the show."
        }
    }

    /**
     * Drives the playhead from whichever clock is authoritative right now.
     *
     * The obvious alternative — incrementing a counter by 16 ms per frame — looks
     * fine for a few seconds and then visibly separates from the music, because
     * nothing ties it to where the audio actually is.
     */
    private fun startPositionUpdates(source: (Long) -> Float?) {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (_running.value) {
                val pos = source(SystemClock.elapsedRealtimeNanos())
                if (pos != null) _positionMs.value = pos
                _degradedClock.value = player?.usingFallbackClock == true
                delay(33)
            }
            _positionMs.value = 0f
        }
    }

    /** Panic stop. Bound to a tap anywhere on the show surface and to volume keys. */
    fun stopShow() {
        runner.stop()
        player?.stop()
        aligner?.stop()
        aligner = null
        alignJob?.cancel()
        alignJob = null
        positionJob?.cancel()
        positionJob = null
        _running.value = false
        _screenCue.value = null
        _positionMs.value = 0f
        _alignStatus.value = AlignStatus()
        ShowService.stop(getApplication())
    }

    fun clear() {
        stopShow()
        job?.cancel()
        player = null
        fingerprint = null
        _mode.value = ShowMode.REHEARSE
        _state.value = UiState.Empty
    }

    override fun onCleared() {
        stopShow()
        ShowSession.unregister()
        torch.stop()
        super.onCleared()
    }

    private companion object {
        const val PREFS = "tunesync"
        const val KEY_CONSENT = "photosensitivity_consent"
        const val KEY_CONSENT_AT = "photosensitivity_consent_at"
        const val KEY_TORCH = "output_torch"
        const val KEY_SCREEN = "output_screen"
        const val KEY_GENTLE = "output_gentle"
    }
}
