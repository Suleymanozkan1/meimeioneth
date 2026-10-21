package com.lexiflow.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lexiflow.core.language.Evaluation
import com.lexiflow.core.language.IssueCategory
import com.lexiflow.core.language.LanguageIssue
import com.lexiflow.data.db.DayCount
import com.lexiflow.ui.theme.BrandGradients
import com.lexiflow.ui.theme.Coral
import com.lexiflow.ui.theme.Danger
import com.lexiflow.ui.theme.Mint
import com.lexiflow.ui.theme.Pink
import com.lexiflow.ui.theme.Sky
import com.lexiflow.ui.theme.Success
import com.lexiflow.ui.theme.Sunshine
import com.lexiflow.ui.theme.Violet
import com.lexiflow.ui.theme.Warning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LexiTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

/** Flat card with a hairline border; optional icon (tinted with [accent], else primary) before the title. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = accent ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
            }
            content()
        }
    }
}

/** Card with a gradient background for heroes and highlights. Content colour is white. */
@Composable
fun GradientCard(brush: Brush, modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(brush)
            .padding(padding),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
            content()
        }
    }
}

/** Quiet tinted square behind an icon. [brush] is kept for existing call sites; the badge is a flat tint now. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun IconBadge(icon: ImageVector, brush: Brush = BrandGradients.violet, size: Dp = 44.dp, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/** Outlined stat tile: small label (with optional icon), big number, optional note. [brush] is kept for call sites. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, brush: Brush = BrandGradients.violet, icon: ImageVector? = null) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EmptyState(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IconBadge(Icons.Default.AutoAwesome, size = 56.dp)
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
        if (actionLabel != null && onAction != null) {
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun LoadingBox(text: String = "Yükleniyor…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
fun NoticeBanner(text: String, warning: Boolean = false) {
    val bg = if (warning) Sunshine.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primaryContainer
    val fg = if (warning) Warning else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (warning) Icons.Default.Warning else Icons.Default.Info, contentDescription = null, tint = fg)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Thin rounded progress bar; colour shifts from coral to sunshine to mint with the value. */
@Composable
fun ScoreBar(label: String, value: Int) {
    val v by animateFloatAsState((value / 100f).coerceIn(0f, 1f), label = "score")
    val color = when { value >= 75 -> Mint; value >= 50 -> Sunshine; else -> Coral }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$value", style = MaterialTheme.typography.labelLarge, color = color)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(v).height(6.dp).clip(CircleShape).background(color))
        }
    }
}

/**
 * Thin progress bar used in sessions and goals: primary on a surface track, or white on a translucent track when
 * drawn on a coloured card (content colour white). [brush] is kept for existing call sites.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun GradientProgress(progress: Float, modifier: Modifier = Modifier, brush: Brush = BrandGradients.hero, height: Dp = 6.dp) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), label = "progress")
    val onColour = LocalContentColor.current == Color.White
    val track = if (onColour) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
    val fill = if (onColour) Color.White else MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(p).height(height).clip(CircleShape).background(fill))
    }
}

private fun categoryColor(c: IssueCategory) = when (c) {
    IssueCategory.GRAMMAR -> Coral
    IssueCategory.COLLOCATION -> Pink
    IssueCategory.WORD_CHOICE -> Violet
    IssueCategory.WORD_USAGE -> Sky
    IssueCategory.MECHANICS -> Sunshine
    IssueCategory.COHERENCE -> Danger
    IssueCategory.REPETITION -> Warning
}

@Composable
fun IssueCard(issue: LanguageIssue) {
    val accent = categoryColor(issue.category)
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clip(MaterialTheme.shapes.medium).background(accent.copy(alpha = 0.09f))) {
        Box(Modifier.width(6.dp).fillMaxSize().background(accent))
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(issue.category.labelTr + if (!issue.certain) " · kontrol et" else "", style = MaterialTheme.typography.labelLarge, color = accent)
            val suggestion = issue.suggestion
            if (issue.original.isNotBlank() && suggestion != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(issue.original, Danger)
                    Text("→", fontWeight = FontWeight.Black)
                    Chip(suggestion, Success)
                }
            }
            Text(issue.explanationTr, style = MaterialTheme.typography.bodyMedium)
            if (issue.examples.isNotEmpty()) Text("Örnek: " + issue.examples.joinToString("  •  "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).background(color.copy(alpha = 0.1f)).padding(horizontal = 8.dp, vertical = 3.dp),
        color = color, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

/** Learner-facing feedback: what was good, the 1-3 key points, corrected version and transparent scores. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EvaluationView(eval: Evaluation, showScores: Boolean = true, title: String = "Geri bildirim") {
    SectionCard(title, accent = Pink, icon = Icons.Default.Lightbulb) {
        if (eval.targetUsage.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                eval.targetUsage.forEach { u -> Chip((if (u.used) "✓ " else "✗ ") + u.lemma, if (u.used) Success else Danger) }
            }
        }
        if (eval.keyPoints.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = Success)
                Spacer(Modifier.width(8.dp))
                Text("Kurallarımızla bir hata bulunmadı.", color = Success, fontWeight = FontWeight.Bold)
            }
        }
        eval.keyPoints.forEach { IssueCard(it) }
        if (eval.corrected != eval.text && eval.keyPoints.any { it.suggestion != null }) {
            Text("Düzeltilmiş sürüm", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(eval.corrected, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
        if (showScores) {
            ScoreBar("Gramer", eval.scores.grammar)
            ScoreBar("Kelime kullanımı", eval.scores.wordUsage)
            ScoreBar("Doğallık", eval.scores.naturalness)
            ScoreBar("Tutarlılık (sezgisel)", eval.scores.coherence)
            ScoreBar("Kelime çeşitliliği", eval.scores.diversity)
            ScoreBar("Görev tamamlama", eval.scores.taskCompletion)
            Text("Toplam: ${eval.scores.overall}/100", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            eval.rationaleTr.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Accessible gradient bar chart for the last 14 days. */
@Composable
fun DayBarChart(data: List<DayCount>, days: List<String>, modifier: Modifier = Modifier, color: Color = Violet) {
    val map = data.associate { it.localDate to it.count }
    val values = days.map { map[it] ?: 0 }
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val desc = days.zip(values).joinToString { "${it.first.takeLast(5)}: ${it.second}" }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.semantics { contentDescription = desc }) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val w = size.width / values.size
            values.forEachIndexed { i, v ->
                val x = i * w + w * 0.18f
                drawRoundRect(track, topLeft = Offset(x, 0f), size = Size(w * 0.64f, size.height), cornerRadius = CornerRadius(14f, 14f))
                val h = size.height * v / max
                if (h > 0) drawRoundRect(
                    Brush.verticalGradient(listOf(color, color.copy(alpha = 0.55f)), startY = size.height - h, endY = size.height),
                    topLeft = Offset(x, size.height - h), size = Size(w * 0.64f, h), cornerRadius = CornerRadius(14f, 14f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(days.first().takeLast(5), style = MaterialTheme.typography.labelSmall)
            Text("bugün", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Navigation must run on the main thread; use after suspending calls inside coroutine scopes. */
suspend fun onMain(block: () -> Unit) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
