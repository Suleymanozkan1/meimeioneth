package com.lexiflow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.lexiflow.ui.components.Chip
import com.lexiflow.ui.components.GradientCard
import com.lexiflow.ui.theme.BrandGradients
import com.lexiflow.ui.theme.Mint
import com.lexiflow.ui.theme.Pink
import com.lexiflow.ui.theme.Violet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.lexiflow.core.competition.BattleMode
import com.lexiflow.core.competition.ClientMessage
import com.lexiflow.core.competition.PlayerResult
import com.lexiflow.core.competition.ServerMessage
import com.lexiflow.core.competition.CompetitionRules
import com.lexiflow.data.remote.BattleSocket
import com.lexiflow.data.remote.LeaderboardEntry
import com.lexiflow.data.remote.RemoteError
import com.lexiflow.data.remote.SocketEvent
import com.lexiflow.data.repo.BotRound
import com.lexiflow.data.repo.RoundOutcome
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.components.EvaluationView
import com.lexiflow.ui.components.IssueCard
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.SectionCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class OnlinePhase {
    data object Idle : OnlinePhase()
    data object Connecting : OnlinePhase()
    data class Lobby(val online: Int) : OnlinePhase()
    data class Queued(val waiting: Int) : OnlinePhase()
    data class InMatch(val match: ServerMessage.MatchFound, val submitted: Boolean) : OnlinePhase()
    data class Result(val result: ServerMessage.RoundResult) : OnlinePhase()
}

data class OnlineState(
    val phase: OnlinePhase = OnlinePhase.Idle,
    val playerId: String? = null,
    val error: String? = null,
    val info: String? = null,
    val leaderboard: List<LeaderboardEntry>? = null,
)

class BattleViewModel(private val c: AppContainer) : ViewModel() {
    val online = MutableStateFlow(OnlineState())
    private val socket = BattleSocket()
    private var job: Job? = null
    private var lastRoom: String? = null
    private var lastMode: BattleMode = BattleMode.FIVE_WORD_SENTENCE

    fun connect() {
        job?.cancel()
        online.update { it.copy(phase = OnlinePhase.Connecting, error = null) }
        job = viewModelScope.launch {
            val target = try { c.backend.battleSocketTarget() } catch (e: RemoteError) { online.update { it.copy(phase = OnlinePhase.Idle, error = e.messageTr) }; return@launch }
            socket.connect(target).collect { ev ->
                when (ev) {
                    SocketEvent.Open -> lastRoom?.let { room -> if (online.value.phase is OnlinePhase.Connecting) socket.send(ClientMessage.Rejoin(room)) }
                    is SocketEvent.Message -> handle(ev.message)
                    is SocketEvent.Closed -> online.update { it.copy(phase = OnlinePhase.Idle, info = "Bağlantı kapandı.") }
                    is SocketEvent.Failure -> online.update { it.copy(phase = OnlinePhase.Idle, error = ev.messageTr + if (lastRoom != null) " Yeniden bağlanırsan tura geri dönebilirsin." else "") }
                }
            }
        }
    }

    private fun handle(m: ServerMessage) {
        when (m) {
            is ServerMessage.Welcome -> online.update { it.copy(phase = OnlinePhase.Lobby(m.onlinePlayers), playerId = m.playerId, error = null) }
            is ServerMessage.Queued -> online.update { it.copy(phase = OnlinePhase.Queued(m.waiting)) }
            is ServerMessage.MatchFound -> { lastRoom = m.roomId; lastMode = m.mode; online.update { it.copy(phase = OnlinePhase.InMatch(m, false), info = null) } }
            is ServerMessage.SubmissionAck -> online.update { s -> (s.phase as? OnlinePhase.InMatch)?.let { s.copy(phase = it.copy(submitted = true)) } ?: s }
            is ServerMessage.PlayerLeft -> online.update { it.copy(info = "${m.displayName} bağlantısı koptu; süre dolana kadar geri dönebilir.") }
            is ServerMessage.RoundResult -> {
                lastRoom = null
                online.update { it.copy(phase = OnlinePhase.Result(m)) }
                viewModelScope.launch { saveOnline(m) }
            }
            is ServerMessage.Error -> online.update { it.copy(error = "Sunucu: ${m.message} (${m.code})") }
            ServerMessage.Pong -> Unit
        }
    }

    private suspend fun saveOnline(r: ServerMessage.RoundResult) {
        val me = online.value.playerId
        // Same deterministic rules as the server, so local copies match the server's scores.
        val evals = r.results.map { p -> Triple(p.displayName, p.playerId == me, CompetitionRules.score(p.text, r.words, lastMode)) }
        val rank = r.ranking.indexOf(me).takeIf { it >= 0 }?.plus(1)
        c.competition.saveResult(lastMode, "ONLINE", r.roomId, r.words, evals, rank)
    }

    fun join(mode: BattleMode) { socket.send(ClientMessage.JoinQueue(mode)) }
    fun leaveQueue() { socket.send(ClientMessage.LeaveQueue); online.update { it.copy(phase = OnlinePhase.Lobby(0)) } }
    fun submit(roomId: String, text: String) { socket.send(ClientMessage.Submit(roomId, text.take(1500))) }
    fun report(roomId: String, playerId: String, reason: String) {
        socket.send(ClientMessage.Report(roomId, playerId, reason))
        viewModelScope.launch { c.competition.report(roomId, playerId, reason) }
        online.update { it.copy(info = "Bildirim gönderildi. Teşekkürler.") }
    }

    fun loadLeaderboard() = viewModelScope.launch {
        try { online.update { it.copy(leaderboard = c.competition.leaderboard(BattleMode.FIVE_WORD_SENTENCE)) } }
        catch (e: RemoteError) { online.update { it.copy(error = e.messageTr) } }
    }

    override fun onCleared() { socket.close(); job?.cancel() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BattleScreen(container: AppContainer, nav: NavController) {
    val vm: BattleViewModel = viewModel(factory = viewModelFactory { initializer { BattleViewModel(container) } })
    val settings by container.settings.settings.collectAsState(initial = null)
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar("Kelime Yarışması", onBack = { nav.popBackStack() })
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Bot antrenmanı") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Online") }, modifier = Modifier.testTag("tab_online"))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("battle"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GradientCard(BrandGradients.fire, padding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚔️ Kelime Düellosu", style = MaterialTheme.typography.headlineSmall)
                    Text("Verilen kelimelerle en iyi cümleyi kur, puanları topla!", style = MaterialTheme.typography.bodyMedium)
                }
            }
            SectionCard("Kurallar") {
                Text("Verilen kelimelerin hepsini kullanarak anlamlı ve doğru bir cümle/kısa hikâye yaz. Puan: gramer %25, doğru kelime kullanımı %25, tutarlılık %15, doğallık %15, kelime çeşitliliği %10, görev tamamlama %10. Puanlama kurallarla yapılır ve sunucuda aynı kurallarla doğrulanır; gerekçesi sonuçta gösterilir.", style = MaterialTheme.typography.bodySmall)
            }
            if (tab == 0) BotPractice(container) else OnlineBattle(vm, settings?.backendConfigured == true && settings?.serverToken != null)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BotPractice(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(BattleMode.FIVE_WORD_SENTENCE) }
    var round by remember { mutableStateOf<BotRound?>(null) }
    var text by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<RoundOutcome?>(null) }
    var left by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        val r = round ?: return@LaunchedEffect
        left = r.mode.roundSeconds
        while (left > 0 && outcome == null) { delay(1000); left-- }
        if (outcome == null && round != null) outcome = container.competition.finishBotRound(r, text)
    }
    NoticeBanner("Bu bir BOT antrenmanıdır. LexiBot gerçek bir oyuncu değildir; cevabı LexiFlow'un örnek cümlelerinden oluşturulur.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { BattleMode.entries.forEach { m -> FilterChip(mode == m, { mode = m }, { Text("${m.labelTr} (${m.roundSeconds}s)") }) } }
    val r = round
    if (r == null || outcome != null) {
        Button(onClick = { scope.launch { outcome = null; text = ""; round = container.competition.newBotRound(mode) } }, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("bot_start"), shape = CircleShape) { Text(if (outcome == null) "Turu başlat" else "Yeni tur") }
    }
    if (r != null) {
        SectionCard("Kelimeler · ⏱ $left sn") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.words.forEach { w -> val used = com.lexiflow.core.language.Morphology.usesWord(text, w); Chip(w + if (used) " ✓" else "", if (used) Mint else Violet) } }
            Text("En az ${r.mode.minWords} kelime. Tüm kelimeler kullanılmalı.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it.take(1500) }, enabled = outcome == null, modifier = Modifier.fillMaxWidth().testTag("bot_input"), minLines = 3, label = { Text("Cevabın") }, shape = MaterialTheme.shapes.medium)
            if (outcome == null) Button(onClick = { scope.launch { outcome = container.competition.finishBotRound(r, text) } }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("bot_submit"), shape = CircleShape) { Text("Gönder ➤") }
        }
    }
    outcome?.let { o ->
        GradientCard(if (o.iWon == true) BrandGradients.mint else BrandGradients.berry) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (o.iWon == true) "Kazandın! 🎉" else "LexiBot kazandı 🤖", style = MaterialTheme.typography.headlineSmall)
                Text("Sen: ${o.mine.scores.overall} · LexiBot (bot): ${o.bot?.scores?.overall}", style = MaterialTheme.typography.titleMedium)
            }
        }
        EvaluationView(o.mine, title = "Senin cevabın")
        o.bot?.let { b -> SectionCard("LexiBot (bot) cevabı") { Text(b.text); Text("Puan: ${b.scores.overall}", style = MaterialTheme.typography.bodySmall) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnlineBattle(vm: BattleViewModel, configured: Boolean) {
    val s by vm.online.collectAsState()
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(BattleMode.FIVE_WORD_SENTENCE) }
    if (!configured) {
        NoticeBanner("Online yarışma için LexiFlow sunucusu gerekir (Ayarlar → Sunucu → Bağlan). Sunucu yokken online mod kapalıdır; sahte oyuncu veya sahte liderlik tablosu gösterilmez.", warning = true)
        return
    }
    s.error?.let { NoticeBanner(it, warning = true) }
    s.info?.let { NoticeBanner(it) }
    when (val p = s.phase) {
        OnlinePhase.Idle -> Button(onClick = { vm.connect() }, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("online_connect"), shape = CircleShape) { Text("Bağlan") }
        OnlinePhase.Connecting -> Text("Bağlanıyor…")
        is OnlinePhase.Lobby -> {
            Text("Çevrim içi gerçek oyuncu: ${p.online}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { BattleMode.entries.forEach { m -> FilterChip(mode == m, { mode = m }, { Text(m.labelTr) }) } }
            Button(onClick = { vm.join(mode) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = CircleShape) { Text("Rakip bul ⚔️") }
        }
        is OnlinePhase.Queued -> {
            Text("Rakip bekleniyor… (kuyrukta ${p.waiting} oyuncu)")
            OutlinedButton(onClick = { vm.leaveQueue() }) { Text("Vazgeç") }
        }
        is OnlinePhase.InMatch -> {
            var left by remember(p.match.roomId) { mutableIntStateOf(((p.match.endsAtEpochMs - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)) }
            LaunchedEffect(p.match.roomId) { while (left > 0) { delay(1000); left = ((p.match.endsAtEpochMs - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0) } }
            SectionCard("${p.match.mode.labelTr} · ⏱ $left sn") {
                Text("Oyuncular: ${p.match.players.joinToString()}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { p.match.words.forEach { w -> val used = com.lexiflow.core.language.Morphology.usesWord(text, w); Chip(w + if (used) " ✓" else "", if (used) Mint else Violet) } }
                OutlinedTextField(text, { text = it.take(1500) }, enabled = !p.submitted && left > 0, modifier = Modifier.fillMaxWidth(), minLines = 3, shape = MaterialTheme.shapes.medium)
                if (!p.submitted) Button(enabled = text.isNotBlank() && left > 0, onClick = { vm.submit(p.match.roomId, text) }, modifier = Modifier.fillMaxWidth(), shape = CircleShape) { Text("Gönder ➤") }
                else Text("Gönderildi. Diğer oyuncular bekleniyor…")
            }
        }
        is OnlinePhase.Result -> {
            SectionCard("Sonuçlar") {
                p.result.results.forEachIndexed { i, r -> PlayerResultView(i + 1, r, r.playerId == s.playerId) { reason -> vm.report(p.result.roomId, r.playerId, reason) } }
            }
            Button(onClick = { text = ""; vm.connect() }) { Text("Yeni tur") }
        }
    }
    SectionCard("Liderlik tablosu (gerçek sonuçlar)") {
        TextButton(onClick = { vm.loadLeaderboard() }) { Text("Yükle") }
        val lb = s.leaderboard
        if (lb != null && lb.isEmpty()) Text("Henüz tamamlanmış oyun yok.")
        lb?.forEachIndexed { i, e -> Text("${i + 1}. ${e.displayName} — ${e.wins} galibiyet / ${e.games} oyun · ort. ${"%.0f".format(e.averageScore)}") }
    }
}

@Composable
private fun PlayerResultView(rank: Int, r: PlayerResult, me: Boolean, onReport: (String) -> Unit) {
    var reported by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(when (rank) { 1 -> BrandGradients.fire; 2 -> BrandGradients.ocean; else -> BrandGradients.violet }), contentAlignment = Alignment.Center) {
                Text("$rank", color = Color.White, style = MaterialTheme.typography.titleSmall)
            }
            Text("${r.displayName}${if (me) " (sen)" else ""}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${r.overall}", style = MaterialTheme.typography.titleLarge, color = if (me) Pink else MaterialTheme.colorScheme.primary)
        }
        if (!r.submitted) Text("Cevap göndermedi.", style = MaterialTheme.typography.bodySmall)
        else {
            Text(r.text)
            if (r.corrected != r.text) Text("Düzeltilmiş: ${r.corrected}", style = MaterialTheme.typography.bodySmall)
            Text("Gramer ${r.scores.grammar} · Kullanım ${r.scores.wordUsage} · Tutarlılık ${r.scores.coherence} · Doğallık ${r.scores.naturalness} · Çeşitlilik ${r.scores.diversity} · Görev ${r.scores.taskCompletion}", style = MaterialTheme.typography.bodySmall)
            r.keyPoints.take(3).forEach { IssueCard(it) }
            r.rationaleTr.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (!me && !reported) Row { TextButton(onClick = { onReport("inappropriate"); reported = true }) { Text("Uygunsuz içerik bildir") } }
        }
    }
}
