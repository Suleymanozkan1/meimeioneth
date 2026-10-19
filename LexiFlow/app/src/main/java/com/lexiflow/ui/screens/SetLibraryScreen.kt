package com.lexiflow.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lexiflow.data.content.WordSetState
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.Chip
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.LoadingBox
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.SectionCard
import com.lexiflow.ui.components.onMain
import com.lexiflow.ui.theme.Mint
import com.lexiflow.ui.theme.Pink
import com.lexiflow.ui.theme.Sky
import com.lexiflow.ui.theme.Sunshine
import com.lexiflow.ui.theme.Violet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ready-made word sets (original LexiFlow content). Adding a set creates a deck; nothing is added before that. */
@Composable
fun SetLibraryScreen(container: AppContainer, nav: NavController) {
    val scope = rememberCoroutineScope()
    var states by remember { mutableStateOf<List<WordSetState>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val previews = remember { mutableStateMapOf<String, String>() }
    suspend fun reload() { states = withContext(Dispatchers.IO) { container.setLibrary.states() } }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        LexiTopBar("Hazır Kelime Setleri", onBack = { nav.popBackStack() })
        val list = states
        if (list == null) { LoadingBox(); return@Column }
        LazyColumn(
            Modifier.fillMaxSize().testTag("set_library"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "LexiFlow için yazılmış özgün setler: her kelimede Türkçe anlam, İngilizce açıklama, örnek cümleler ve sık yapılan hatalar var. " +
                        "Setler seviyeye göre gruplu: her seviyenin başında o seviyedeki bütün kelimeleri içeren bir seviye paketi var. " +
                        "Bir seti eklediğinde ayrı bir deste olarak FSRS tekrarına girer; istemezsen desteyi silebilirsin.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            notice?.let { item { NoticeBanner(it) } }
            // Grouped by level: each level starts with its level pack, then the topical sets whose words are mostly that level.
            list.groupBy { it.info.group.ifBlank { "Diğer" } }.forEach { (group, sets) ->
                item(key = "group_$group") {
                    Text(levelTitle(group), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("set_group_$group"))
                }
                items(sets, key = { it.info.id }) { s ->
                    val info = s.info
                    SectionCard(info.title, Modifier.testTag("set_${info.id}")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Chip(info.level, levelColor(info.level))
                            Chip(info.category, Sunshine)
                            Chip("${info.wordCount} kelime", Sky)
                            if (s.deckId != null) Chip("Eklendi", Mint)
                        }
                        Text(info.description, style = MaterialTheme.typography.bodyMedium)
                        previews[info.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (s.deckId == null) {
                                Button(
                                    enabled = busy == null,
                                    onClick = {
                                        busy = info.id
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) { runCatching { container.setLibrary.install(info.id) } }
                                            notice = r.fold(
                                                { rep ->
                                                    "\"${info.title}\" eklendi: ${rep.added} kelime." +
                                                        if (rep.skippedDuplicates.isEmpty()) "" else " ${rep.skippedDuplicates.size} kelime zaten başka bir destende vardı, tekrar eklenmedi."
                                                },
                                                { "Set eklenemedi: ${it.message ?: "bilinmeyen hata"}" },
                                            )
                                            reload()
                                            busy = null
                                        }
                                    },
                                    modifier = Modifier.testTag("add_set_${info.id}"),
                                ) { Text(if (busy == info.id) "Ekleniyor…" else "Desteye ekle") }
                            } else {
                                OutlinedButton(onClick = { nav.navigate(Routes.deck(s.deckId)) }) { Text("Desteyi aç") }
                                Button(onClick = { nav.navigate(Routes.review(deck = s.deckId)) }) { Text("Çalış") }
                            }
                            if (previews[info.id] == null) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val words = withContext(Dispatchers.IO) { container.setLibrary.words(info.id) }
                                        onMain {
                                            previews[info.id] = words.take(10).joinToString("\n") { w -> "• ${w.lemma} — ${w.senses.firstOrNull()?.meaningTr.orEmpty()}" } +
                                                if (words.size > 10) "\n… ve ${words.size - 10} kelime daha" else ""
                                        }
                                    }
                                }) { Text("Önizle") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun levelTitle(level: String) = when (level) {
    "A1" -> "A1 · Başlangıç"
    "A2" -> "A2 · Temel"
    "B1" -> "B1 · Orta"
    "B2" -> "B2 · Orta üstü"
    "C1" -> "C1 · İleri"
    "C2" -> "C2 · Uzman"
    else -> level
}

private fun levelColor(level: String) = when {
    level.startsWith("A") -> Mint
    level.startsWith("B1") -> Sky
    level.startsWith("B2") -> Violet
    level.startsWith("C") -> Pink
    else -> Sunshine
}
