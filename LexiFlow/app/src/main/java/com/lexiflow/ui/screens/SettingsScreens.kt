package com.lexiflow.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lexiflow.data.remote.RemoteError
import com.lexiflow.data.settings.Accent
import com.lexiflow.data.settings.AppSettings
import com.lexiflow.data.settings.LockDifficulty
import com.lexiflow.data.settings.LockPool
import com.lexiflow.di.AppContainer
import com.lexiflow.notifications.LockScreenWorker
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var cefr by remember { mutableStateOf("B1") }
    var goal by remember { mutableStateOf(40f) }
    var lock by remember { mutableStateOf(true) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("onboarding"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        com.lexiflow.ui.components.GradientCard(com.lexiflow.ui.theme.BrandGradients.hero, padding = 24.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("👋 ✨ 🚀", style = MaterialTheme.typography.headlineMedium)
                Text("LexiFlow'a hoş geldin", style = MaterialTheme.typography.headlineMedium)
                Text("Kelimeyi görünce tanıyorsun ama konuşurken aklına gelmiyor mu? LexiFlow tanımayı (FSRS kartları) ve aktif kullanımı (hatırlama, cümle kurma, konuşma) ayrı ayrı çalıştırır.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        SectionCard("Seviyen (CEFR)") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("A2", "B1", "B2", "C1").forEach { l -> FilterChip(selected = cefr == l, onClick = { cefr = l }, label = { Text(l) }) }
            }
        }
        SectionCard("Günlük hedef: ${goal.roundToInt()} XP") {
            Slider(value = goal, onValueChange = { goal = it }, valueRange = 20f..150f, steps = 12)
            Text("Hedefi istediğin zaman değiştirebilirsin. Kaçırılan gün için ceza yok.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Kilit ekranında kelime soruları") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Telefonu açtığında kısa sorular göster", Modifier.weight(1f))
                Switch(checked = lock, onCheckedChange = { lock = it })
            }
            Text("Android'in izin verdiği bildirimlerle çalışır; güvenli kilit ekranını aşmaz. Varsayılan: günde en fazla 8 soru, 22:00–08:00 sessiz.", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            scope.launch {
                container.settings.update { it.copy(onboardingDone = true, cefr = cefr, dailyGoalXp = goal.roundToInt(), lockScreenEnabled = lock) }
                runCatching { LockScreenWorker.schedule(ctx, lock) }
                withContext(Dispatchers.Main) { onDone() }
                if (lock && Build.VERSION.SDK_INT >= 33) runCatching { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("onboarding_start"), shape = androidx.compose.foundation.shape.CircleShape) { Text("Hadi başlayalım 🚀", style = MaterialTheme.typography.titleMedium) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(container: AppContainer, nav: NavController) {
    val s by container.settings.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var url by remember(s.backendUrl) { mutableStateOf(s.backendUrl) }
    var name by remember(s.displayName) { mutableStateOf(s.displayName) }
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { container.settings.update(t) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ayarlar", style = MaterialTheme.typography.headlineMedium)
        message?.let { NoticeBanner(it) }

        SectionCard("Öğrenme") {
            Text("Seviye (CEFR)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("A2", "B1", "B2", "C1").forEach { l -> FilterChip(selected = s.cefr == l, onClick = { update { it.copy(cefr = l) } }, label = { Text(l) }) }
            }
            Text("Günlük hedef: ${s.dailyGoalXp} XP")
            Slider(value = s.dailyGoalXp.toFloat(), onValueChange = { v -> update { it.copy(dailyGoalXp = v.roundToInt()) } }, valueRange = 20f..150f, modifier = Modifier.testTag("goal_slider"))
            Text("Günde yeni kart: ${s.newCardsPerDay}")
            Slider(value = s.newCardsPerDay.toFloat(), onValueChange = { v -> update { it.copy(newCardsPerDay = v.roundToInt()) } }, valueRange = 0f..50f)
            Text("FSRS hedef hatırlama oranı: %${(s.desiredRetention * 100).roundToInt()}")
            Slider(value = s.desiredRetention.toFloat(), onValueChange = { v -> update { it.copy(desiredRetention = (v * 100).roundToInt() / 100.0) } }, valueRange = 0.8f..0.95f)
            Text("Yüksek oran = daha sık tekrar, daha az unutma.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tekrar kartında önce anlam ve boşluklu cümle", Modifier.weight(1f))
                Switch(checked = s.reviewMeaningFirst, onCheckedChange = { v -> update { it.copy(reviewMeaningFirst = v) } }, modifier = Modifier.testTag("review_meaning_first"))
            }
            Text("Açıkken kelime ve okunuşu \"Cevabı göster\"e kadar gizli kalır; kelimeyi anlamdan hatırlarsın. Kapalıyken kart kelimeyle başlar.", style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Kilit ekranı ve bildirimler") {
            OutlinedButton(onClick = { nav.navigate(Routes.LOCKSCREEN) }) { Text("Kilit ekranı soru ayarları") }
        }

        SectionCard("Telaffuz ve ses") {
            Text("Referans aksan (Dinleme, Dikte, Shadowing ve kartlar)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Accent.entries.forEach { a -> FilterChip(selected = s.accent == a, onClick = { update { it.copy(accent = a) } }, label = { Text(a.labelTr) }, modifier = Modifier.testTag("accent_${a.name}")) }
            }
            TtsVoiceSettings(s) { t -> update(t) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Çevrim dışı konuşma tanımayı tercih et", Modifier.weight(1f))
                Switch(checked = s.preferOfflineSpeech, onCheckedChange = { v -> update { it.copy(preferOfflineSpeech = v) } })
            }
            Text("Ses Android'in konuşma tanıma hizmetince işlenir. LexiFlow ham ses kaydı saklamaz; yalnızca metin dökümü ve sonuç saklanır. Çevrim dışı tercih açıkken cihazdaki model kullanılır (cihaz destekliyorsa); aksi hâlde hizmet sağlayıcının sunucuları kullanılabilir.", style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Gizlilik ve veri saklama") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Yazdığım cevapları sakla (istatistik ve tekrar için)", Modifier.weight(1f))
                Switch(checked = s.saveAnswerTexts, onCheckedChange = { v -> update { it.copy(saveAnswerTexts = v) } })
            }
            Text("Konuşma geçmişini sakla: ${if (s.keepSpeakingDays == 0) "süresiz" else "${s.keepSpeakingDays} gün"}")
            Slider(value = s.keepSpeakingDays.toFloat(), onValueChange = { v -> update { it.copy(keepSpeakingDays = v.roundToInt().coerceAtLeast(1)) } }, valueRange = 1f..180f)
            Text("Telaffuz sonuçlarını sakla: ${s.keepPronunciationDays} gün")
            Slider(value = s.keepPronunciationDays.toFloat(), onValueChange = { v -> update { it.copy(keepPronunciationDays = v.roundToInt().coerceAtLeast(1)) } }, valueRange = 1f..180f)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { container.maintenance.deleteConversations(); message = "Konuşma geçmişi silindi." } }) { Text("Konuşmaları sil") }
                OutlinedButton(onClick = { scope.launch { container.maintenance.deletePronunciation(); message = "Telaffuz sonuçları silindi." } }) { Text("Telaffuzları sil") }
                OutlinedButton(onClick = { scope.launch { container.maintenance.deleteAnswerTexts(); message = "Kayıtlı cevap metinleri silindi." } }) { Text("Cevap metinlerini sil") }
            }
        }

        SectionCard("Sunucu (AI ve online yarışma) — isteğe bağlı") {
            Text("LexiFlow çevrim dışı tam çalışır. AI hikâyeleri, AI konuşma, kelime zenginleştirme ve online yarışma için kendi LexiFlow sunucunu bağla (backend/README.md). API anahtarları yalnızca sunucuda tutulur.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Sunucu adresi (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Görünen ad (yarışmada)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        container.settings.update { it.copy(backendUrl = url.trim(), serverToken = null, playerId = null) }
                        message = try {
                            val h = container.backend.health()
                            container.backend.register(name.ifBlank { "Learner" })
                            container.settings.update { it.copy(aiEnabled = h.ai) }
                            "Bağlandı. AI: ${if (h.ai) "etkin" else "sunucuda kapalı"}."
                        } catch (e: RemoteError) { e.messageTr } catch (e: Exception) { "Bağlantı başarısız: ${e.message}" }
                    }
                }) { Text("Bağlan") }
                TextButton(onClick = { update { it.copy(backendUrl = "", serverToken = null, playerId = null, aiEnabled = false) }; url = "" }) { Text("Bağlantıyı kaldır") }
            }
            Text(if (s.aiAvailable) "Durum: bağlı, AI etkin" else if (s.serverToken != null) "Durum: bağlı, AI kapalı" else "Durum: çevrim dışı mod", style = MaterialTheme.typography.labelMedium)
        }

        SectionCard("İçe aktarma ve desteler") {
            OutlinedButton(onClick = { nav.navigate(Routes.IMPORT) }) { Text("Anki / CSV içe aktar") }
        }
        SectionCard("Hakkında") {
            Text("LexiFlow ${com.lexiflow.BuildConfig.VERSION_NAME} · FSRS-6 zamanlayıcı (open-spaced-repetition, BSD-3) · İçerik: LexiFlow özgün + kamu malı kaynaklar. Ayrıntılar docs/ klasöründe.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LockSettingsCard(container: AppContainer) {
    val s by container.settings.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { container.settings.update(t) }
    SectionCard("Ayarlar") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kilit ekranı öğrenmesi", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Switch(checked = s.lockScreenEnabled, modifier = Modifier.testTag("lock_switch"), onCheckedChange = { v ->
                update { it.copy(lockScreenEnabled = v) }
                LockScreenWorker.schedule(ctx, v)
                if (v && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        Text("Günde en fazla ${s.lockQuestionsPerDay} soru")
        Slider(value = s.lockQuestionsPerDay.toFloat(), onValueChange = { v -> update { it.copy(lockQuestionsPerDay = v.roundToInt()) } }, valueRange = 1f..20f)
        Text("Sorular arası en az ${s.lockIntervalMinutes} dk")
        Slider(value = s.lockIntervalMinutes.toFloat(), onValueChange = { v -> update { it.copy(lockIntervalMinutes = (v / 15).roundToInt() * 15) } }, valueRange = 15f..240f)
        Text("Sessiz saatler: ${s.quietStartHour}:00 – ${s.quietEndHour}:00")
        Slider(value = s.quietStartHour.toFloat(), onValueChange = { v -> update { it.copy(quietStartHour = v.roundToInt()) } }, valueRange = 0f..23f, steps = 22)
        Slider(value = s.quietEndHour.toFloat(), onValueChange = { v -> update { it.copy(quietEndHour = v.roundToInt()) } }, valueRange = 0f..23f, steps = 22)
        Text("Kelime havuzu")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LockPool.entries.forEach { p -> FilterChip(selected = s.lockPool == p, onClick = { update { it.copy(lockPool = p) } }, label = { Text(p.labelTr) }) }
        }
        Text("Zorluk")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LockDifficulty.entries.forEach { d -> FilterChip(selected = s.lockDifficulty == d, onClick = { update { it.copy(lockDifficulty = d) } }, label = { Text(d.labelTr) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Türkçe anlamı göster", Modifier.weight(1f))
            Switch(checked = s.lockShowTurkish, onCheckedChange = { v -> update { it.copy(lockShowTurkish = v) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Güvenli kilit ekranında içeriği gizle", Modifier.weight(1f))
            Switch(checked = s.lockHideOnSecureScreen, onCheckedChange = { v -> update { it.copy(lockHideOnSecureScreen = v) } })
        }
        Text("Gizle açıkken kilit ekranında yalnızca \"Yeni bir kelime sorusu var\" yazar; soru kilit açılınca görünür. Kendi yazdığın cümleler hiçbir zaman kilit ekranında gösterilmez.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Speed, voice choice and a test button for the reference voice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TtsVoiceSettings(s: com.lexiflow.data.settings.AppSettings, update: ((com.lexiflow.data.settings.AppSettings) -> com.lexiflow.data.settings.AppSettings) -> Unit) {
    val tts = rememberTts()
    val uk = s.accent == Accent.UK
    var rate by remember(s.ttsRate) { mutableStateOf(s.ttsRate.toFloat()) }
    var voices by remember { mutableStateOf<List<com.lexiflow.speech.VoiceOption>>(emptyList()) }
    LaunchedEffect(uk) {
        // The engine starts asynchronously; wait briefly for it before listing voices.
        repeat(20) { if (!tts.ready) kotlinx.coroutines.delay(150) }
        voices = tts.voices(uk)
    }
    Text("Konuşma hızı: ${"%.1f".format(rate)}×", style = MaterialTheme.typography.labelLarge)
    androidx.compose.material3.Slider(value = rate, onValueChange = { rate = (it * 10).roundToInt() / 10f }, valueRange = 0.6f..1.4f,
        onValueChangeFinished = { update { it.copy(ttsRate = rate.toDouble()) } }, modifier = Modifier.testTag("tts_rate"))
    Text("Ses", style = MaterialTheme.typography.labelLarge)
    val chosen = if (uk) s.ttsVoiceUk else s.ttsVoiceUs
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = chosen == null, onClick = { update { if (uk) it.copy(ttsVoiceUk = null) else it.copy(ttsVoiceUs = null) } },
            label = { Text("Otomatik (en doğal ses)") }, modifier = Modifier.testTag("tts_voice_auto"))
        voices.take(8).forEach { v ->
            FilterChip(selected = chosen == v.name, onClick = { update { if (uk) it.copy(ttsVoiceUk = v.name) else it.copy(ttsVoiceUs = v.name) } }, label = { Text(v.labelTr) })
        }
    }
    if (tts.ready && voices.isEmpty()) {
        Text("Cihazda bu aksan için ayrı ses listesi bulunamadı; varsayılan ses kullanılır. Daha doğal sesler için Android Ayarlar → Metin okuma çıkışı'ndan Google'ın ses paketlerini indirebilirsin.",
            style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = {
        com.lexiflow.speech.TtsPreferences.rate = rate
        tts.speak("I'd rather take the early train, if that's all right with you.", uk)
    }, modifier = Modifier.testTag("tts_try")) { Text("🔊 Sesi dene") }
}
