package com.lexiflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lexiflow.core.language.Evaluation
import com.lexiflow.core.language.LanguageIssue
import com.lexiflow.data.db.DayCount
import com.lexiflow.ui.theme.Danger
import com.lexiflow.ui.theme.Success
import com.lexiflow.ui.theme.Warning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LexiTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") }
        },
        actions = { actions() },
    )
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Card(modifier = modifier.semantics(mergeDescendants = true) {}, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun LoadingBox(text: String = "Yükleniyor…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(text)
        }
    }
}

@Composable
fun NoticeBanner(text: String, warning: Boolean = false) {
    Card(colors = CardDefaults.cardColors(containerColor = if (warning) Color(0x33D97706) else MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (warning) Icons.Default.Warning else Icons.Default.Info, contentDescription = null, tint = if (warning) Warning else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ScoreBar(label: String, value: Int) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("$value", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp),
            color = when { value >= 75 -> Success; value >= 50 -> Warning; else -> Danger })
    }
}

@Composable
fun IssueCard(issue: LanguageIssue) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(issue.category.labelTr + if (!issue.certain) " · kontrol et" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (issue.original.isNotBlank() && issue.suggestion != null) {
                Text("✗ ${issue.original}   →   ✓ ${issue.suggestion}", fontWeight = FontWeight.SemiBold)
            }
            Text(issue.explanationTr, style = MaterialTheme.typography.bodyMedium)
            if (issue.examples.isNotEmpty()) Text("Örnek: " + issue.examples.joinToString("  •  "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Learner-facing feedback: what was good, the 1-3 key points, corrected version and transparent scores. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EvaluationView(eval: Evaluation, showScores: Boolean = true, title: String = "Geri bildirim") {
    SectionCard(title) {
        if (eval.targetUsage.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                eval.targetUsage.forEach { u -> AssistChip(onClick = {}, label = { Text((if (u.used) "✓ " else "✗ ") + u.lemma) }) }
            }
        }
        if (eval.keyPoints.isEmpty()) Text("Kurallarımızla bir hata bulunmadı. 👍", color = Success)
        eval.keyPoints.forEach { IssueCard(it) }
        if (eval.corrected != eval.text && eval.keyPoints.any { it.suggestion != null }) {
            Text("Düzeltilmiş sürüm:", style = MaterialTheme.typography.labelLarge)
            Text(eval.corrected, fontWeight = FontWeight.Medium)
        }
        if (showScores) {
            ScoreBar("Gramer", eval.scores.grammar)
            ScoreBar("Kelime kullanımı", eval.scores.wordUsage)
            ScoreBar("Doğallık", eval.scores.naturalness)
            ScoreBar("Tutarlılık (sezgisel)", eval.scores.coherence)
            ScoreBar("Kelime çeşitliliği", eval.scores.diversity)
            ScoreBar("Görev tamamlama", eval.scores.taskCompletion)
            Text("Toplam: ${eval.scores.overall}/100", fontWeight = FontWeight.Bold)
            eval.rationaleTr.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Simple accessible bar chart for the last 14 days. */
@Composable
fun DayBarChart(data: List<DayCount>, days: List<String>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val map = data.associate { it.localDate to it.count }
    val values = days.map { map[it] ?: 0 }
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val desc = days.zip(values).joinToString { "${it.first.takeLast(5)}: ${it.second}" }
    Column(modifier.semantics { contentDescription = desc }) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val w = size.width / values.size
            values.forEachIndexed { i, v ->
                val h = size.height * v / max
                drawRoundRect(color, topLeft = Offset(i * w + w * 0.15f, size.height - h), size = Size(w * 0.7f, h), cornerRadius = CornerRadius(6f, 6f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(days.first().takeLast(5), style = MaterialTheme.typography.labelSmall)
            Text("bugün", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Navigation must run on the main thread; use after suspending calls inside coroutine scopes. */
suspend fun onMain(block: () -> Unit) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
