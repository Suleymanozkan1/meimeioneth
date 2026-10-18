package com.lexiflow.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lexiflow.core.exercise.QuickQuestionType
import com.lexiflow.data.repo.LockAnswerResult
import com.lexiflow.data.repo.LockQuestion
import com.lexiflow.data.repo.SelfRating
import com.lexiflow.di.AppContainer
import com.lexiflow.notifications.LockScreenWorker
import com.lexiflow.notifications.NotificationHelper
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.EmptyState
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.LoadingBox
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.SectionCard
import com.lexiflow.ui.theme.Danger
import com.lexiflow.ui.theme.Success
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LockScreenScreen(container: AppContainer, nav: NavController) {
    val ctx = LocalContext.current
    val recent by container.lockScreen.observeRecent().collectAsState(initial = emptyList())
    var notice by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar("Kilit Ekranı Soruları", onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("lockscreen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Nasıl çalışır?") {
                Text("Telefonunu açtığında kilit ekranında kısa bir kelime sorusu görürsün: İngilizceden Türkçeye, Türkçeden İngilizceye, boşluk doldurma veya kendi cümleni kurma. Seçenek düğmelerine kilit ekranından dokunabilirsin; cevap, açıklama ve örnek cümle hemen gösterilir.")
                Text("Doğru cevaptan sonra: Biliyorum / Zorlandım / Tanıyorum ama kullanamıyorum. Kart tekrar zamanı geldiyse bu cevap FSRS geçmişine işlenir; değilse yalnızca aktif hatırlama kaydı tutulur (erken tekrar yapılmaz).", style = MaterialTheme.typography.bodySmall)
                Text("Sınırlar: LexiFlow sistem kilit ekranını değiştirmez veya güvenli kilidi aşmaz. Yazılı cevap (cümle kurma) için Android çoğu cihazda kilidin açılmasını ister. Bildirim içeriğinin kilit ekranında görünmesi telefonunun \"Kilit ekranında bildirimler\" ayarına bağlıdır. Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) kullanıyorsun.", style = MaterialTheme.typography.bodySmall)
                if (!NotificationHelper.canPost(ctx)) NoticeBanner("Bildirim izni kapalı. Kilit ekranı soruları için izin ver (anahtarı açınca sorulur) veya sistem ayarlarından aç.", warning = true)
            }
            LockSettingsCard(container)
            notice?.let { NoticeBanner(it) }
            Button(onClick = {
                LockScreenWorker.sendNow(ctx)
                notice = if (NotificationHelper.canPost(ctx)) "Test sorusu bildirim olarak gönderildi. Ekranı kilitleyip kontrol edebilirsin." else "Bildirim izni yok; soru gönderilemedi."
            }, modifier = Modifier.fillMaxWidth().testTag("lock_test")) { Text("Şimdi test sorusu gönder") }
            OutlinedButton(onClick = { nav.navigate(Routes.lockQuestion("new")) }, modifier = Modifier.fillMaxWidth()) { Text("Bir soruyu uygulamada çöz") }
            SectionCard("Son sorular") {
                if (recent.isEmpty()) Text("Henüz soru yok.")
                val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                recent.take(15).forEach { q ->
                    Text("${fmt.format(Date(q.createdAt))} · ${runCatching { QuickQuestionType.valueOf(q.type).labelTr }.getOrDefault(q.type)} · ${q.outcome ?: "cevaplanmadı"}", style = MaterialTheme.typography.bodySmall)
                    Text(q.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

/** Opens a lock-screen question inside the app (notification tap) or creates a new one ("new"). */
@Composable
fun LockQuestionScreen(container: AppContainer, nav: NavController, id: String) {
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf<LockQuestion?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LockAnswerResult?>(null) }
    var text by remember { mutableStateOf("") }
    var rated by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        container.contentLoader.ensureSeeded()
        question = if (id == "new") container.lockScreen.createQuestion() else id.toLongOrNull()?.let { container.lockScreen.load(it) }
        question?.entity?.answeredAt?.let { _ ->
            result = question?.let { q -> container.lockScreen.answerChoice(q.entity.id, q.entity.chosenIndex ?: -1, "APP") }
        }
        NotificationHelper.cancel(container.appContext)
        loaded = true
    }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar("Kelime sorusu", onBack = { nav.popBackStack() })
        val q = question
        when {
            !loaded -> LoadingBox()
            q == null -> EmptyState("Soru bulunamadı veya henüz kelime yok.")
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val type = QuickQuestionType.valueOf(q.entity.type)
                Text(type.labelTr, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(q.entity.prompt, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("lockq_prompt"))
                val r = result
                if (r == null) {
                    if (type == QuickQuestionType.PRODUCTION) {
                        OutlinedTextField(text, { text = it }, label = { Text("Cümlen") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = text.isNotBlank(), onClick = { scope.launch { result = container.lockScreen.answerProduction(q.entity.id, text, "APP") } }) { Text("Gönder") }
                    } else {
                        q.options.forEachIndexed { i, o ->
                            OutlinedButton(onClick = { scope.launch { result = container.lockScreen.answerChoice(q.entity.id, i, "APP") } }, modifier = Modifier.fillMaxWidth().testTag("lockq_option_$i")) { Text(o) }
                        }
                    }
                } else {
                    SectionCard {
                        Text(r.titleTr, color = if (r.correct) Success else Danger, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("lockq_result"))
                        Text(r.bodyTr)
                    }
                    if (r.canSelfRate && !rated) {
                        SelfRating.entries.forEach { s ->
                            OutlinedButton(onClick = { scope.launch { container.lockScreen.selfRate(q.entity.id, s, "APP"); rated = true } }, modifier = Modifier.fillMaxWidth()) { Text(s.labelTr) }
                        }
                    } else if (rated) NoticeBanner("Kaydedildi.")
                    Button(onClick = { nav.navigate(Routes.lockQuestion("new")) { popUpTo(Routes.lockQuestion(id)) { inclusive = true } } }) { Text("Yeni soru") }
                }
            }
        }
    }
}
