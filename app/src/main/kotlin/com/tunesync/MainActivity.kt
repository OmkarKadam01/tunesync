package com.tunesync

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.tunesync.core.model.Curve
import com.tunesync.ui.Amber
import com.tunesync.ui.BeatMapView
import com.tunesync.ui.ConsentDialog
import com.tunesync.ui.EmptyScreen
import com.tunesync.ui.FailedScreen
import com.tunesync.ui.Ink
import com.tunesync.ui.MainViewModel
import com.tunesync.ui.OutputControls
import com.tunesync.ui.Panel
import com.tunesync.ui.ShowSurface
import com.tunesync.ui.StatRow
import com.tunesync.ui.StyleChips
import com.tunesync.ui.TextMuted
import com.tunesync.ui.TextPrimary
import com.tunesync.ui.UiState
import com.tunesync.ui.WorkingScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

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

    override fun onPause() {
        super.onPause()
        // Phase 1 has no foreground service yet, so a backgrounded app must not
        // leave the torch strobing in someone's pocket.
        viewModel.stopShow()
    }
}

@Composable
private fun App(viewModel: MainViewModel, onPick: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val running by viewModel.running.collectAsState()
    val screenCue by viewModel.screenCue.collectAsState()
    val needsConsent by viewModel.needsConsent.collectAsState()

    Box(Modifier.fillMaxSize().background(Ink)) {
        when (val s = state) {
            UiState.Empty -> EmptyScreen(onPick)

            is UiState.Working -> WorkingScreen(s.stage.label, s.progress) { viewModel.clear() }

            is UiState.Failed -> FailedScreen(s.message, s.canRetry, onPick)

            is UiState.Loaded -> LoadedScreen(s, viewModel, onPick)
        }

        // Full-screen colour panel, drawn above everything while a cue is live.
        val cue = screenCue
        if (running && cue != null) {
            val alpha = if (cue.curve == Curve.STEP) 1f else cue.strength.coerceIn(0.15f, 1f)
            ShowSurface(
                active = true,
                colour = Color(cue.rgb or 0xFF000000.toInt()).copy(alpha = alpha),
                onStop = { viewModel.stopShow() },
            )
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
private fun LoadedScreen(s: UiState.Loaded, viewModel: MainViewModel, onPick: () -> Unit) {
    val running by viewModel.running.collectAsState()
    val playbackError by viewModel.playbackError.collectAsState()
    var positionMs by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(running) {
        // Drives only the playhead. The show's own timing never touches Compose.
        while (running) {
            positionMs += 16f
            kotlinx.coroutines.delay(16)
        }
        if (!running) positionMs = 0f
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
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
        val canPlay = s.output.anyEnabled
        Button(
            onClick = { if (running) viewModel.stopShow() else viewModel.startShow() },
            enabled = running || canPlay,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) Panel else Amber,
                contentColor = if (running) TextPrimary else Ink,
                disabledContainerColor = Panel,
                disabledContentColor = TextMuted,
            ),
        ) {
            Text(if (running) "Stop" else "Play show", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}
