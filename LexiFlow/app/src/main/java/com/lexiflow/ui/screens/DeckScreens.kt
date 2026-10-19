package com.lexiflow.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.lexiflow.core.active.Skill
import com.lexiflow.core.fsrs.CardState
import com.lexiflow.data.content.RichInfo
import com.lexiflow.data.remote.RemoteError
import com.lexiflow.data.repo.NewExample
import com.lexiflow.data.repo.NewSense
import com.lexiflow.data.repo.NewWord
import com.lexiflow.data.repo.WordRepository
import com.lexiflow.data.repo.toCore
import com.lexiflow.data.settings.Accent
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.EmptyState
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.ScoreBar
import com.lexiflow.ui.components.SectionCard
import com.lexiflow.ui.components.onMain
import kotlinx.coroutines.launch

@Composable
fun DecksScreen(container: AppContainer, nav: NavController) {
    val decks by remember { container.words.observeDecks() }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<com.lexiflow.data.db.DeckEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Yeni deste") },
            text = {
                OutlinedTextField(newName, { newName = it.take(80) }, label = { Text("Deste adı") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_deck_name"))
            },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    val name = newName.trim()
                    creating = false; newName = ""
                    scope.launch { val id = container.words.createDeck(name, "USER"); onMain { nav.navigate(Routes.deck(id)) } }
                }, modifier = Modifier.testTag("confirm_new_deck")) { Text("Oluştur") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Vazgeç") } },
        )
    }
    deleting?.let { deck ->
        DeleteDeckDialog(deck, decks.firstOrNull { it.deck.id == deck.id }?.wordCount ?: 0,
            onConfirm = { deleting = null; scope.launch { container.words.deleteDeck(deck.id) } },
            onDismiss = { deleting = null })
    }
    val results by remember(query) { container.words.search(query) }.collectAsState(initial = emptyList())
    Scaffold(floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.IMPORT) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("İçe aktar") })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).testTag("decks"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Desteler", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.navigate(Routes.SET_LIBRARY) }, modifier = Modifier.weight(1f).testTag("open_set_library")) { Text("📚 Hazır setler") }
                    OutlinedButton(onClick = { creating = true }, modifier = Modifier.weight(1f).testTag("new_deck")) { Text("＋ Yeni deste") }
                }
            }
            item {
                OutlinedTextField(query, { query = it }, label = { Text("Kelime ara") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (query.isNotBlank()) {
                items(results) { w -> ListItem(headlineContent = { Text(w.lemma) }, supportingContent = { Text(w.pos ?: "") }, modifier = Modifier.clickable { nav.navigate(Routes.word(w.id)) }) }
            } else {
                if (decks.isEmpty()) item { EmptyState("Deste yükleniyor veya henüz deste yok.") }
                items(decks) { d ->
                    SectionCard(d.deck.name, Modifier.clickable { nav.navigate(Routes.deck(d.deck.id)) }) {
                        Text("${d.wordCount} kelime · ${d.cardCount} kart · ${d.dueCount} tekrar zamanı · ${d.newCount} yeni")
                        if (d.deck.description.isNotBlank()) Text(d.deck.description, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { nav.navigate(Routes.review(deck = d.deck.id)) }) { Text("Tekrar") }
                            OutlinedButton(onClick = { nav.navigate(Routes.wordEdit(deck = d.deck.id)) }) { Text("Kelime ekle") }
                            IconButton(onClick = { deleting = d.deck }, modifier = Modifier.testTag("delete_deck_${d.deck.id}")) {
                                Icon(Icons.Default.Delete, contentDescription = "Desteyi sil")
                            }
                        }
                    }
                }
                item { OutlinedButton(onClick = { nav.navigate(Routes.WORDBOOK) }) { Text("Kişisel kelime defterim") } }
            }
        }
    }
}

@Composable
fun DeckDetailScreen(container: AppContainer, nav: NavController, deckId: Long) {
    val words by remember(deckId) { container.words.observeWords(deckId) }.collectAsState(initial = emptyList())
    val decks by remember { container.words.observeDecks() }.collectAsState(initial = emptyList())
    val deck = decks.firstOrNull { it.deck.id == deckId }?.deck
    var confirm by remember { mutableStateOf(false) }
    var toolBusy by remember { mutableStateOf(false) }
    var toolNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (confirm && deck != null) {
        DeleteDeckDialog(deck, words.size,
            onConfirm = {
                confirm = false
                scope.launch { container.words.deleteDeck(deckId); onMain { nav.popBackStack() } }
            },
            onDismiss = { confirm = false })
    }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar(deck?.name ?: "Deste", onBack = { nav.popBackStack() }) {
            IconButton(onClick = { nav.navigate(Routes.wordEdit(deck = deckId)) }) { Icon(Icons.Default.Add, contentDescription = "Kelime ekle") }
            if (deck != null) IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, contentDescription = "Desteyi sil") }
        }
        LazyColumn(contentPadding = PaddingValues(8.dp), modifier = Modifier.testTag("deck_words")) {
            if (words.isEmpty()) item { EmptyState("Bu destede kelime yok.", "Kelime ekle") { nav.navigate(Routes.wordEdit(deck = deckId)) } }
            if (words.isNotEmpty()) item {
                SectionCard("Deste araçları") {
                    Text("LexiFlow kendi sözlüğündeki kelimelere İngilizce açıklama, örnek cümle, seviye ve notlar ekleyebilir (senin anlamların ve tekrar geçmişin değişmez).", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !toolBusy, modifier = Modifier.weight(1f).testTag("deck_enrich"), onClick = {
                            toolBusy = true
                            scope.launch {
                                val r = container.deckTools.enrichDeck(deckId)
                                toolNotice = "${r.words} kelime zenginleştirildi: ${r.examples} örnek cümle eklendi, ${r.levels} kelimeye seviye atandı."
                                toolBusy = false
                            }
                        }) { Text("✨ Zenginleştir") }
                        OutlinedButton(enabled = !toolBusy && words.count { it.cefr != null } > 0, modifier = Modifier.weight(1f).testTag("deck_split"), onClick = {
                            toolBusy = true
                            scope.launch {
                                val moved = container.deckTools.splitByLevel(deckId)
                                toolBusy = false
                                toolNotice = if (moved.isEmpty()) "Seviyesi bilinen kelime yok. Önce \"Zenginleştir\"i dene." else
                                    "Seviye desteleri oluşturuldu: " + moved.entries.joinToString(", ") { "${it.key} (${it.value})" } + "."
                                if (moved.isNotEmpty() && words.size == moved.values.sum()) onMain { nav.popBackStack() }
                            }
                        }) { Text("📊 Seviyeye göre böl") }
                    }
                    if (toolBusy) Text("Çalışıyor…", style = MaterialTheme.typography.bodySmall)
                    toolNotice?.let { NoticeBanner(it) }
                }
            }
            // Words grouped by level (A1 → C2, then unknown).
            val order = listOf("A1", "A2", "B1", "B2", "C1", "C2")
            words.groupBy { it.cefr?.takeIf { l -> l in order } }
                .toSortedMap(compareBy<String?> { l -> if (l == null) order.size else order.indexOf(l) })
                .forEach { (level, group) ->
                    item(key = "level_${level ?: "none"}") {
                        Text(if (level != null) "$level · ${group.size} kelime" else "Seviyesi bilinmiyor · ${group.size} kelime",
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp).testTag("level_header_${level ?: "none"}"))
                    }
                    items(group, key = { it.id }) { w ->
                        ListItem(headlineContent = { Text(w.lemma, fontWeight = FontWeight.SemiBold) }, supportingContent = { Text(listOfNotNull(w.pos, w.ipaUs).joinToString(" · ")) },
                            modifier = Modifier.clickable { nav.navigate(Routes.word(w.id)) })
                    }
                }
        }
    }
}

/** Deleting a deck removes its words, cards and review history, so it always asks first. */
@Composable
private fun DeleteDeckDialog(deck: com.lexiflow.data.db.DeckEntity, wordCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deste silinsin mi?") },
        text = {
            Text(buildString {
                append("\"${deck.name}\" destesi, içindeki $wordCount kelime, kartları ve tekrar geçmişiyle birlikte silinecek. Bu işlem geri alınamaz.")
                if (deck.source == "BUILTIN") append("\n\nHazır LexiFlow destesi, uygulama yeniden açıldığında baştan yüklenir.")
                if (deck.source.startsWith("IMPORT")) append("\n\nDosyayı istersen yeniden içe aktarabilirsin.")
                if (deck.source.startsWith("SET:")) append("\n\nBu hazır seti istediğin zaman \"Hazır setler\" ekranından yeniden ekleyebilirsin.")
            })
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm_delete_deck")) { Text("Sil", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
fun WordbookScreen(container: AppContainer, nav: NavController) {
    val words by remember { container.words.observePersonal() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        LexiTopBar("Kişisel Kelime Defteri", onBack = { nav.popBackStack() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kendi eklediğin kelimeler, kendi örnek cümlelerin ve hikâye/kültür modlarından kaydettiklerin.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { scope.launch { val d = container.words.personalDeckId(); onMain { nav.navigate(Routes.wordEdit(deck = d)) } } }) { Text("Yeni kelime ekle") }
        }
        LazyColumn(contentPadding = PaddingValues(8.dp)) {
            if (words.isEmpty()) item { EmptyState("Defterin boş. Kendi kelimelerini ekle; her kelime FSRS ve aktif kullanım planına girer.") }
            items(words) { w -> ListItem(headlineContent = { Text(w.lemma) }, supportingContent = { Text(w.notes ?: w.pos ?: "") }, modifier = Modifier.clickable { nav.navigate(Routes.word(w.id)) }) }
        }
    }
}

@Composable
fun WordDetailScreen(container: AppContainer, nav: NavController, wordId: Long) {
    val word by remember(wordId) { container.words.observeWord(wordId) }.collectAsState(initial = null)
    val senses by remember(wordId) { container.words.observeSenses(wordId) }.collectAsState(initial = emptyList())
    val examples by remember(wordId) { container.words.observeExamples(wordId) }.collectAsState(initial = emptyList())
    val cards by remember(wordId) { container.words.observeCards(wordId) }.collectAsState(initial = emptyList())
    val settings by container.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val tts = rememberTts()
    val snack = remember { SnackbarHostState() }
    var notice by remember { mutableStateOf<String?>(null) }
    var newExample by remember { mutableStateOf("") }
    var skills by remember { mutableStateOf<Map<Long, Map<Skill, com.lexiflow.core.active.SkillState>>>(emptyMap()) }
    LaunchedEffect(cards) { skills = cards.associate { it.id to container.active.states(it.id) } }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            LexiTopBar(word?.lemma ?: "Kelime", onBack = { nav.popBackStack() }) {
                IconButton(onClick = {
                    scope.launch {
                        container.words.deleteWord(wordId)
                        val r = snack.showSnackbar("Kelime silindi", actionLabel = "Geri al")
                        if (r == SnackbarResult.ActionPerformed) container.words.undoDelete()?.let { id -> onMain { nav.navigate(Routes.word(id)) { popUpTo(Routes.word(wordId)) { inclusive = true } } } }
                        else onMain { nav.popBackStack() }
                    }
                }) { Icon(Icons.Default.Delete, contentDescription = "Sil") }
            }
            val w = word ?: return@Column
            val rich = WordRepository.rich(w.richJson)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard {
                    Text(w.lemma, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(w.pos, w.cefr, w.register).joinToString(" · "))
                    Text("UK ${w.ipaUk ?: "—"}   US ${w.ipaUs ?: "—"}")
                    Text("Heceler: ${w.syllables ?: "—"} · Vurgu: ${w.stress ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { tts.speak(w.lemma, uk = true) }) { Text("🔊 UK") }
                        OutlinedButton(onClick = { tts.speak(w.lemma, uk = false) }) { Text("🔊 US") }
                    }
                    w.notes?.let { Text("Not: $it", style = MaterialTheme.typography.bodySmall) }
                }
                notice?.let { NoticeBanner(it) }
                senses.forEach { sense ->
                    val card = cards.firstOrNull { it.senseId == sense.id }
                    SectionCard("${sense.position + 1}. ${sense.meaningTr}") {
                        if (sense.label != sense.meaningTr) Text(sense.label, style = MaterialTheme.typography.labelMedium)
                        sense.definitionEn?.let { Text(it) }
                        val ex = examples.filter { it.senseId == sense.id }
                        Text("${ex.size} örnek cümle", style = MaterialTheme.typography.labelMedium)
                        ex.forEach { e ->
                            Text("• ${e.en}")
                            e.tr?.let { Text("   $it", style = MaterialTheme.typography.bodySmall) }
                            e.noteTr?.let { Text("   💡 $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (card != null) {
                            Text("FSRS: ${CardState.of(card.state).name} · S=${"%.1f".format(card.stability)} gün · D=${"%.1f".format(card.difficulty)} · ${card.reps} tekrar, ${card.lapses} unutma", style = MaterialTheme.typography.bodySmall)
                            skills[card.id]?.filterKeys { it in Skill.ACTIVE }?.forEach { (sk, st) -> ScoreBar(sk.labelTr, (st.mastery * 100).toInt()) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { scope.launch { container.active.flagCantUse(card.id); notice = "Aktif üretim listesine eklendi." } }) { Text("Kullanamıyorum") }
                                TextButton(onClick = { scope.launch { container.study.setSuspended(card.id, !card.suspended) } }) { Text(if (card.suspended) "Askıdan al" else "Askıya al") }
                            }
                        }
                    }
                }
                SectionCard("Zengin bilgi") { RichChips(rich) }
                SectionCard("Kendi örnek cümlen") {
                    OutlinedTextField(newExample, { newExample = it }, label = { Text("\"${w.lemma}\" ile bir cümle") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = newExample.isNotBlank(), onClick = {
                        scope.launch {
                            val sense = senses.firstOrNull() ?: return@launch
                            val token = com.lexiflow.core.language.Morphology.findUsage(newExample, w.lemma)
                            if (token < 0) { notice = "Cümlende \"${w.lemma}\" (veya bir çekimi) yok."; return@launch }
                            val surface = com.lexiflow.core.language.Morphology.tokens(newExample)[token]
                            val answer = Regex("(?i)(?<![A-Za-z])" + Regex.escape(surface) + "(?![A-Za-z])").find(newExample)?.value ?: surface
                            container.words.addExamples(sense.id, listOf(NewExample(newExample.trim(), null, "user", answer)), "USER")
                            newExample = ""; notice = "Örnek eklendi; boşluk doldurma alıştırmalarında kullanılacak."
                        }
                    }) { Text("Ekle") }
                }
                if (settings?.aiAvailable == true) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            notice = "AI örnek üretiyor…"
                            notice = try { "${container.enrichment.enrich(wordId)} yeni örnek ve anlam eklendi (doğrulandı)." } catch (e: RemoteError) { e.messageTr }
                        }
                    }) { Text("AI ile zenginleştir (10+ örnek)") }
                } else {
                    Text("AI ile zenginleştirme için Ayarlar'dan sunucu bağla. Yerleşik kelimelerde 10+ insan yazımı örnek zaten var.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { nav.navigate(Routes.wordEdit(word = wordId)) }) { Text("Düzenle") }
            }
        }
    }
}

/** Add a word (with one or more senses) or edit an existing word's basic fields. */
@Composable
fun WordEditScreen(container: AppContainer, nav: NavController, deckId: Long?, wordId: Long?) {
    val scope = rememberCoroutineScope()
    var lemma by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf("") }
    var ipa by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val senses = remember { mutableStateListOf(Triple("", "", "")) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(wordId) {
        if (wordId == null) return@LaunchedEffect
        container.db.wordDao().get(wordId)?.let { w -> lemma = w.lemma; pos = w.pos.orEmpty(); ipa = w.ipaUs.orEmpty(); notes = w.notes.orEmpty() }
        senses.clear()
        container.db.wordDao().senses(wordId).forEach { senses += Triple(it.meaningTr, it.definitionEn.orEmpty(), "") }
    }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar(if (wordId == null) "Kelime ekle" else "Kelimeyi düzenle", onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(lemma, { lemma = it }, label = { Text("İngilizce kelime / ifade") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("edit_lemma"))
            OutlinedTextField(pos, { pos = it }, label = { Text("Kelime türü (noun, verb…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ipa, { ipa = it }, label = { Text("IPA (isteğe bağlı)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            senses.forEachIndexed { i, (tr, def, ex) ->
                SectionCard("Anlam ${i + 1} (ayrı kart olur)") {
                    OutlinedTextField(tr, { senses[i] = Triple(it, def, ex) }, label = { Text("Türkçe anlam") }, modifier = Modifier.fillMaxWidth().testTag("edit_meaning_$i"))
                    OutlinedTextField(def, { senses[i] = Triple(tr, it, ex) }, label = { Text("İngilizce açıklama") }, modifier = Modifier.fillMaxWidth())
                    if (wordId == null) OutlinedTextField(ex, { senses[i] = Triple(tr, def, it) }, label = { Text("Örnek cümle") }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (wordId == null) TextButton(onClick = { senses += Triple("", "", "") }) { Text("+ Başka anlam ekle") }
            OutlinedTextField(notes, { notes = it }, label = { Text("Not") }, modifier = Modifier.fillMaxWidth())
            error?.let { NoticeBanner(it, warning = true) }
            Button(modifier = Modifier.fillMaxWidth().testTag("edit_save"), onClick = {
                scope.launch {
                    if (lemma.isBlank() || senses.all { it.first.isBlank() }) { error = "Kelime ve en az bir Türkçe anlam gerekli."; return@launch }
                    if (wordId != null) {
                        val w = container.db.wordDao().get(wordId) ?: return@launch
                        container.words.updateWord(w.copy(lemma = lemma.trim(), pos = pos.ifBlank { null }, ipaUs = ipa.ifBlank { null }, notes = notes.ifBlank { null }))
                        container.db.wordDao().senses(wordId).forEachIndexed { i, s -> senses.getOrNull(i)?.let { container.words.updateSense(s.copy(meaningTr = it.first, definitionEn = it.second.ifBlank { null })) } }
                        onMain { nav.popBackStack() }
                    } else {
                        val deck = deckId ?: container.words.personalDeckId()
                        val built = senses.filter { it.first.isNotBlank() }.map { (tr, def, ex) ->
                            val idx = com.lexiflow.core.language.Morphology.findUsage(ex, lemma)
                            val answer = if (idx >= 0) com.lexiflow.core.language.Morphology.tokens(ex)[idx].let { t -> Regex("(?i)(?<![A-Za-z])" + Regex.escape(t) + "(?![A-Za-z])").find(ex)?.value ?: t } else null
                            NewSense(tr, tr, def.ifBlank { null }, if (answer != null) listOf(NewExample(ex.trim(), null, "user", answer)) else emptyList())
                        }
                        val id = container.words.insertWord(deck, NewWord(lemma.trim(), pos.ifBlank { null }, ipaUs = ipa.ifBlank { null }, rich = RichInfo(), notes = notes.ifBlank { null },
                            tags = listOf("wordbook"), senses = built), "USER")
                        onMain { nav.navigate(Routes.word(id)) { popUpTo(Routes.wordEdit(deck = deckId ?: -1)) { inclusive = true } } }
                    }
                }
            }) { Text("Kaydet") }
        }
    }
}
