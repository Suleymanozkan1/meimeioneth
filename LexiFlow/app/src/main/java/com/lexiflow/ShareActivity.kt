package com.lexiflow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lexiflow.data.repo.QuickAddDraft
import com.lexiflow.data.repo.QuickAddRepository
import com.lexiflow.data.repo.QuickAddResult
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.theme.LexiFlowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives text shared ("Paylaş") or selected ("LexiFlow'a ekle" in the text-selection menu) in another app and adds
 * the chosen word to the personal wordbook. Nothing is saved until the learner taps "Ekle".
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = sharedText(intent).take(QuickAddRepository.MAX_TEXT)
        // Only the plain text is used; every other extra of the (exported) intent is ignored.
        intent?.replaceExtras(null as Bundle?)
        val container = (application as LexiFlowApp).container
        setContent { LexiFlowTheme { ShareScreen(container, text, onClose = { finish() }, onOpen = ::openWord) } }
    }

    private fun openWord() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lexiflow://wordbook"), this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        fun sharedText(intent: Intent?): String = runCatching {
            when (intent?.action) {
                Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                else -> null
            }
        }.getOrNull().orEmpty()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareScreen(container: AppContainer, sharedText: String, onClose: () -> Unit, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<QuickAddDraft?>(null) }
    var word by remember { mutableStateOf("") }
    var meaning by remember { mutableStateOf("") }
    // Reading the dictionary assets can take a moment: do it off the main thread.
    LaunchedEffect(sharedText) {
        val d = withContext(Dispatchers.IO) { container.quickAdd.draft(sharedText) }
        word = d.word; meaning = d.meaningSuggestion.orEmpty(); draft = d
    }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<QuickAddResult?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("share_add"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Kişisel Kelime Defterine ekle", style = MaterialTheme.typography.headlineSmall)
            if (sharedText.isBlank()) Text("Paylaşılan metin boş.", style = MaterialTheme.typography.bodyMedium)
            val draft = draft ?: run { Text("Hazırlanıyor…"); return@Column }
            draft.sentence?.let { Text("Seçilen metin: \"$it\"", style = MaterialTheme.typography.bodyMedium) }
            if (draft.tokens.size > 1) {
                Text("Eklenecek kelimeye dokun:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.tokens.forEach { t ->
                        FilterChip(selected = word.equals(t, true), onClick = {
                            word = t.lowercase()
                            scope.launch { meaning = withContext(Dispatchers.IO) { container.quickAdd.meaningOf(t) }.orEmpty() }
                        }, label = { Text(t) })
                    }
                }
            }
            OutlinedTextField(word, { word = it.take(60); result = null }, label = { Text("Kelime / kalıp") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("share_word"))
            OutlinedTextField(meaning, { meaning = it.take(200) }, label = { Text("Türkçe anlamı (boş bırakılırsa otomatik bulunur)") },
                modifier = Modifier.fillMaxWidth().testTag("share_meaning"))
            result?.let { NoticeBanner(it.noticeTr, warning = !it.ok) }
            if (result?.ok == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Defteri aç") }
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Kapat") }
                }
            } else {
                Button(enabled = word.isNotBlank() && !busy, onClick = {
                    scope.launch {
                        busy = true
                        result = withContext(Dispatchers.IO) { container.quickAdd.add(word, meaning, draft.sentence) }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("share_save")) { Text(if (busy) "Ekleniyor ve zenginleştiriliyor…" else "Ekle") }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Vazgeç") }
            }
        }
    }
}
