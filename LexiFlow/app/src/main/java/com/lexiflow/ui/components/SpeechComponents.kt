package com.lexiflow.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.lexiflow.core.pronunciation.AsrHypothesis
import com.lexiflow.core.pronunciation.AudioSignals
import com.lexiflow.speech.SpeechInput
import com.lexiflow.speech.SpeechState

/**
 * Microphone button: asks for RECORD_AUDIO only when tapped, explains why, and returns the n-best
 * transcripts. No audio is stored by the app.
 */
@Composable
fun MicButton(
    label: String = "Konuşarak cevapla",
    languageTag: String = "en-US",
    preferOffline: Boolean = true,
    modifier: Modifier = Modifier,
    onResult: (List<AsrHypothesis>, AudioSignals) -> Unit,
) {
    val ctx = LocalContext.current
    val speech = remember { SpeechInput(ctx) }
    val state by speech.state.collectAsState()
    var denied by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { speech.cancel() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) speech.start(languageTag, preferOffline)
    }
    LaunchedEffect(state) {
        val s = state
        if (s is SpeechState.Result) { onResult(s.hypotheses, s.audio); speech.reset() }
    }
    Column(modifier) {
        val listening = state is SpeechState.Listening
        val processing = state is SpeechState.Processing
        FilledTonalButton(onClick = {
            when {
                listening -> speech.stop()
                processing -> speech.cancel()
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> speech.start(languageTag, preferOffline)
                else -> launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }) {
            Icon(if (listening || processing) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
            Text(
                when {
                    listening -> "  Dinliyorum… (durdur)"
                    processing -> "  Algılanıyor… (iptal)"
                    else -> "  $label"
                },
            )
        }
        when (val s = state) {
            is SpeechState.Listening -> Text(s.partial.ifBlank { "Konuşmaya başla; bitince durdur'a bas veya sus." }, style = MaterialTheme.typography.bodySmall)
            is SpeechState.Error -> Text(s.messageTr, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }
        if (denied) Text("Mikrofon izni verilmedi. Sesli cevap için izin gerekir; yazarak da devam edebilirsin.", style = MaterialTheme.typography.bodySmall)
    }
}
