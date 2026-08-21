package com.tunesync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.tunesync.core.model.Curve
import com.tunesync.ui.Amber
import com.tunesync.ui.BeatMapView
import com.tunesync.ui.ConsentDialog
import com.tunesync.ui.EmptyScreen
import com.tunesync.ui.FailedScreen
import com.tunesync.ui.Ink
import com.tunesync.ui.ListenStatus
import com.tunesync.ui.MainViewModel
import com.tunesync.ui.MicRationale
import com.tunesync.ui.ModeTabs
import com.tunesync.ui.OutputControls
import com.tunesync.ui.Panel
import com.tunesync.ui.ShowMode
import com.tunesync.ui.ShowSurface
import com.tunesync.ui.StatRow
import com.tunesync.ui.StyleChips
import com.tunesync.ui.TextMuted
import com.tunesync.ui.TextPrimary
import com.tunesync.ui.UiState
import com.tunesync.ui.WorkingScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var onMicResult: ((Boolean) -> Unit)? = null

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onMicResult?.invoke(granted)
        onMicResult = null
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The show runs either way; without it there is just no shade control. */ }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureMic(onGranted: () -> Unit) {
        if (hasMic()) {
            onGranted()
            return
        }
        onMicResult = { granted -> if (granted) onGranted() }
        requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun ensureNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // Persist the grant so the track survives a reboot.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.importTrack(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The show needs the screen on and bright; both are released on stop.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel)) {
                App(
                    viewModel = viewModel,
                    onPick = { pickAudio.launch(arrayOf("audio/*")) },
                    hasMic = ::hasMic,
                    onNeedMic = { granted -> ensureMic(granted) },
                    onNeedNotifications = ::ensureNotifications,
                )
            }
        }
    }

    /** Volume keys are the other panic stop: reachable without looking. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (viewModel.running.value) {
                viewModel.stopShow()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // The show survives backgrounding now: a foreground service keeps it alive and
    // puts a stop control in the shade, which is what makes that safe.
}

@Composable
private fun App(
    viewModel: MainViewModel,
    onPick: () -> Unit,
    hasMic: () -> Boolean,
    onNeedMic: (() -> Unit) -> Unit,
    onNeedNotifications: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val running by viewModel.running.collectAsState()
    val screenCue by viewModel.screenCue.collectAsState()
    val needsConsent by viewModel.needsConsent.collectAsState()

    Box(Modifier.fillMaxSize().background(Ink)) {
        when (val s = state) {
            UiState.Empty -> EmptyScreen(onPick)

            is UiState.Working -> WorkingScreen(s.stage.label, s.progress) { viewModel.clear() }

            is UiState.Failed -> FailedScreen(s.message, s.canRetry, onPick)

            is UiState.Loaded -> LoadedScreen(
                s, viewModel, onPick, hasMic, onNeedMic, onNeedNotifications,
            )
        }

        // Full-screen colour panel while the show runs.
        //
        // It stays mounted between cues, going dark rather than disappearing.
        // Unmounting it left the panic-stop tap target existing only during a
        // flash, so for most of a show there was nothing to tap.
        val loadedState = state as? UiState.Loaded
        if (running && loadedState?.output?.screen == true) {
            val cue = screenCue
            val colour = if (cue == null) {
                Ink
            } else {
                val alpha = if (cue.curve == Curve.STEP) 1f else cue.strength.coerceIn(0.15f, 1f)
                Color(cue.rgb or 0xFF000000.toInt()).copy(alpha = alpha)
            }
            ShowSurface(active = true, colour = colour, onStop = { viewModel.stopShow() })
        }

        if (needsConsent && state is UiState.Loaded) {
            ConsentDialog(
                onContinue = { viewModel.grantConsent(reducedFlash = false) },
                onReduced = { viewModel.grantConsent(reducedFlash = true) },
            )
        }
    }
}

@Composable
private fun LoadedScreen(
    s: UiState.Loaded,
    viewModel: MainViewModel,
    onPick: () -> Unit,
    hasMic: () -> Boolean,
    onNeedMic: (() -> Unit) -> Unit,
    onNeedNotifications: () -> Unit,
) {
    val running by viewModel.running.collectAsState()
    val playbackError by viewModel.playbackError.collectAsState()
    // Sampled from the audio clock in the view model, not counted up here.
    val positionMs by viewModel.positionMs.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val alignStatus by viewModel.alignStatus.collectAsState()
    val listening = mode == ShowMode.LISTEN
    var micAsked by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Beat map", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onPick) { Text("Change song", color = TextMuted, fontSize = 13.sp) }
        }

        Spacer(Modifier.height(14.dp))
        StatRow(
            tempo = s.map.analysis.tempoBpm,
            beats = s.map.beats.size,
            confidence = s.map.analysis.confidence,
            peakHz = s.show.peakFlashRateHz,
        )

        Spacer(Modifier.height(18.dp))
        BeatMapView(s.map, s.peaks, s.show, positionMs, Modifier.fillMaxWidth())

        Spacer(Modifier.height(16.dp))
        Text(
            s.show.report.summary(),
            color = TextMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(18.dp))
        ModeTabs(listening) { wantListen ->
            viewModel.setMode(if (wantListen) ShowMode.LISTEN else ShowMode.REHEARSE)
            micAsked = false
        }

        if (listening) {
            Spacer(Modifier.height(14.dp))
            if (!hasMic() && !micAsked) {
                MicRationale(
                    onAllow = {
                        micAsked = true
                        onNeedMic { }
                    },
                    onCancel = { viewModel.setMode(ShowMode.REHEARSE) },
                )
            } else {
                ListenStatus(alignStatus, running)
            }
        }

        Spacer(Modifier.height(18.dp))
        StyleChips(s.style) { viewModel.setStyle(it) }

        Spacer(Modifier.height(16.dp))
        OutputControls(
            torch = s.output.torch,
            screen = s.output.screen,
            gentle = s.output.gentle,
            torchAvailable = s.torchAvailable,
            onTorchChange = { viewModel.setTorchEnabled(it) },
            onScreenChange = { viewModel.setScreenEnabled(it) },
            onGentleChange = { viewModel.setGentle(it) },
        )

        playbackError?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(message, color = Color(0xFFF08A8F), fontSize = 13.sp)
        }

        Spacer(Modifier.height(20.dp))
        val micReady = !listening || hasMic()
        val canStart = s.output.anyEnabled && micReady
        Button(
            onClick = {
                when {
                    running -> viewModel.stopShow()
                    listening -> {
                        onNeedNotifications()
                        onNeedMic { viewModel.startListening() }
                    }
                    else -> {
                        onNeedNotifications()
                        viewModel.startShow()
                    }
                }
            },
            enabled = running || canStart,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) Panel else Amber,
                contentColor = if (running) TextPrimary else Ink,
                disabledContainerColor = Panel,
                disabledContentColor = TextMuted,
            ),
        ) {
            Text(
                when {
                    running && listening -> "Stop listening"
                    running -> "Stop"
                    listening -> "Start listening"
                    else -> "Play show"
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}
