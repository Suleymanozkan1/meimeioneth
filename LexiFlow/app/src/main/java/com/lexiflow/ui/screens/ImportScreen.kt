package com.lexiflow.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lexiflow.core.importer.DuplicateKind
import com.lexiflow.core.importer.RowError
import com.lexiflow.core.importer.TargetField
import com.lexiflow.data.importer.UnsupportedApkgException
import com.lexiflow.data.repo.ImportPreview
import com.lexiflow.data.repo.ImportReport
import com.lexiflow.data.repo.ImportRowPreview
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.LoadingBox
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun ImportScreen(container: AppContainer, nav: NavController) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    val mapping = remember { mutableStateListOf<TargetField>() }
    var rows by remember { mutableStateOf<List<ImportRowPreview>>(emptyList()) }
    var rowErrors by remember { mutableStateOf<List<RowError>>(emptyList()) }
    var deckName by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ImportReport?>(null) }

    fun open(uri: Uri) = scope.launch {
        busy = true; error = null; report = null
        try {
            val p = container.importer.load(uri)
            preview = p
            mapping.clear(); mapping.addAll(p.mapping)
            deckName = p.suggestedDeckName
        } catch (e: UnsupportedApkgException) { error = e.message } catch (e: Exception) { error = "Dosya okunamadı: ${e.message}" }
        busy = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { open(it) } }
    LaunchedEffect(preview, mapping.toList()) {
        val p = preview ?: return@LaunchedEffect
        val (r, e) = container.importer.previewRows(p, mapping.toList())
        rows = r; rowErrors = e
    }

    Column(Modifier.fillMaxSize()) {
        LexiTopBar("Anki / CSV içe aktar", onBack = { nav.popBackStack() })
        if (busy) { LoadingBox("Dosya işleniyor…"); return@Column }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("import"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Desteklenen formatlar") {
                Text("• CSV / TSV (UTF-8, UTF-16 veya Windows-1254), başlıklı veya başlıksız")
                Text("• Anki → Dışa aktar → \"Notes in Plain Text (.txt)\" (#separator, #html başlıkları dahil)")
                Text("• Anki → \"Anki Deck Package (.apkg)\" — \"Support older Anki versions\" işaretli olmalı. Tekrar geçmişi (revlog) varsa FSRS ile yeniden hesaplanır.")
                Text("Medya (ses/görsel) aktarılmaz. Yalnızca kendi destelerini veya kullanma hakkın olan desteleri aktar.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("import_pick")) { Text("Dosya seç") }
            }
            error?.let { NoticeBanner(it, warning = true) }
            report?.let { r ->
                SectionCard("İçe aktarma raporu") {
                    Text("Deste: ${r.deckName}", fontWeight = FontWeight.SemiBold)
                    Text("✓ ${r.imported} yeni kelime, ${r.newSenses} mevcut kelimeye yeni anlam")
                    Text("↺ ${r.duplicatesSkipped.size} aynı kart atlandı")
                    Text("✗ ${r.errors.size} hatalı satır")
                    if (r.historyCards > 0) Text("⏱ ${r.historyCards} kartın ${r.historyReviews} gerçek tekrar kaydı aktarıldı")
                    r.duplicatesSkipped.take(10).forEach { Text("  • $it", style = MaterialTheme.typography.bodySmall) }
                    r.errors.take(20).forEach { Text("  Satır ${it.lineNumber}: ${it.messageTr} (${it.raw})", style = MaterialTheme.typography.bodySmall) }
                    r.notesTr.forEach { Text("ℹ $it", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { nav.popBackStack() }) { Text("Bitti") }
                }
            }
            val p = preview
            if (p != null && report == null) {
                SectionCard("Önizleme: ${p.fileName}") {
                    Text("Format: ${p.format.labelTr} · Kodlama: ${p.encoding} · ${p.table.rows.size} satır")
                    p.infoTr.forEach { Text("ℹ $it", style = MaterialTheme.typography.bodySmall) }
                    OutlinedTextField(deckName, { deckName = it }, label = { Text("Deste adı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (p.rawText != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("İlk satır sütun başlığı", Modifier.weight(1f))
                        Switch(checked = p.table.header != null, modifier = Modifier.testTag("import_header"), onCheckedChange = { v ->
                            val np = container.importer.withHeader(p, v)
                            preview = np; mapping.clear(); mapping.addAll(np.mapping)
                        })
                    }
                }
                SectionCard("Alan eşleştirme") {
                    val headers = p.table.header ?: List(mapping.size) { "Sütun ${it + 1}" }
                    headers.forEachIndexed { i, h ->
                        if (i < mapping.size) Row(Modifier.fillMaxWidth()) {
                            Text(h, Modifier.width(120.dp))
                            FieldPicker(mapping[i]) { mapping[i] = it }
                        }
                    }
                    Text("İlk satır örneği: " + (p.table.rows.firstOrNull()?.joinToString(" | ")?.take(160) ?: "—"), style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Satırlar (ilk ${rows.size})") {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Column {
                            rows.take(20).forEach { r ->
                                val tag = when (r.duplicate) { DuplicateKind.NONE -> "Yeni"; DuplicateKind.EXACT -> "Aynısı var"; DuplicateKind.SAME_WORD_NEW_SENSE -> "Yeni anlam" }
                                Text("[$tag] ${r.candidate.word} — ${r.candidate.meaningTr}${r.candidate.example?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (rowErrors.isNotEmpty()) Text("${rowErrors.size} satır içe aktarılamayacak (rapor sonunda listelenir).", color = MaterialTheme.colorScheme.error)
                }
                Button(enabled = mapping.contains(TargetField.WORD) && (mapping.contains(TargetField.MEANING_TR) || mapping.contains(TargetField.DEFINITION_EN)),
                    onClick = {
                        scope.launch {
                            busy = true
                            report = runCatching { container.importer.import(p, mapping.toList(), deckName.ifBlank { p.suggestedDeckName }) }
                                .onFailure { error = "İçe aktarma başarısız: ${it.message}" }.getOrNull()
                            busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth().testTag("import_run")) { Text("İçe aktar") }
            }
        }
    }
}

@Composable
private fun FieldPicker(value: TargetField, onChange: (TargetField) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text(value.labelTr) }
    DropdownMenu(open, { open = false }) {
        TargetField.entries.forEach { f -> DropdownMenuItem(text = { Text(f.labelTr) }, onClick = { onChange(f); open = false }) }
    }
}
