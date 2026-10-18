package com.lexiflow.core.language

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Serializable
data class TargetWord(
    val lemma: String,
    /** Optional preposition/particle patterns the sense normally takes, e.g. ["to", "about"] for "reluctant". */
    val expectedPatterns: List<String> = emptyList(),
)

@Serializable
data class TargetUsage(
    val lemma: String,
    val used: Boolean,
    val surfaceForm: String? = null,
    val note: String? = null,
)

@Serializable
data class ScoreBreakdown(
    val grammar: Int,
    val wordUsage: Int,
    val coherence: Int,
    val naturalness: Int,
    val diversity: Int,
    val taskCompletion: Int,
) {
    val overall: Int
        get() = (grammar * 0.25 + wordUsage * 0.25 + coherence * 0.15 + naturalness * 0.15 + diversity * 0.1 + taskCompletion * 0.1).roundToInt()
}

@Serializable
data class Evaluation(
    val text: String,
    val scores: ScoreBreakdown,
    val issues: List<LanguageIssue>,
    /** The 1-3 most important points to show the learner. */
    val keyPoints: List<LanguageIssue>,
    val corrected: String,
    val targetUsage: List<TargetUsage>,
    val rationaleTr: List<String>,
    val wordCount: Int,
    /** True when only deterministic rules were applied (no AI review). */
    val deterministicOnly: Boolean = true,
)

data class EvaluationTask(
    val targets: List<TargetWord> = emptyList(),
    /** When true every target must appear for full task completion. */
    val requireAllTargets: Boolean = true,
    val minWords: Int = 4,
    val maxWords: Int = 400,
)

/**
 * Deterministic evaluator shared by the app (offline feedback) and the server (competition scoring).
 * It never claims semantic certainty: coherence is a transparent heuristic and is labelled as such.
 * AI feedback, when available, is layered on top but cannot override target-usage checks.
 */
object SentenceEvaluator {

    private val functionWords = setOf(
        "the", "a", "an", "to", "of", "in", "on", "at", "for", "with", "and", "but", "or", "so", "because", "is", "are",
        "was", "were", "be", "been", "i", "you", "he", "she", "it", "we", "they", "my", "your", "his", "her", "our",
        "their", "this", "that", "these", "those", "have", "has", "had", "do", "does", "did", "not", "will", "would",
        "can", "could", "should", "must", "from", "by", "as", "when", "while", "if", "then", "there", "who", "which",
        "what", "me", "him", "us", "them", "about", "into", "than", "after", "before", "very", "just", "also", "it's",
        "i'm", "don't", "didn't", "isn't", "wasn't", "am", "until", "although", "since", "though",
    )

    fun evaluate(text: String, task: EvaluationTask = EvaluationTask()): Evaluation {
        val clean = sanitize(text)
        val tokens = Morphology.tokens(clean)
        val wordCount = tokens.size
        val issues = GrammarRules.check(clean).toMutableList()
        val rationale = mutableListOf<String>()

        // Target usage (authoritative, deterministic).
        val usages = task.targets.map { target ->
            val idx = Morphology.findUsage(clean, target.lemma)
            if (idx < 0) {
                TargetUsage(target.lemma, used = false, note = "Kullanılmadı")
            } else {
                val surface = tokens[idx]
                val note = patternNote(tokens, idx, target)
                if (note != null) {
                    issues += LanguageIssue("pattern_${target.lemma}", IssueCategory.WORD_USAGE, -1, -1, surface, null, note, 1, certain = false)
                }
                TargetUsage(target.lemma, used = true, surfaceForm = surface, note = note)
            }
        }

        val listIssue = wordListIssue(tokens, task.targets)
        if (listIssue != null) issues += listIssue
        val repetition = repetitionIssues(tokens)
        issues += repetition

        // Scores.
        val grammarPenalty = issues.filter { it.category == IssueCategory.GRAMMAR || it.category == IssueCategory.MECHANICS }
            .sumOf { if (it.category == IssueCategory.MECHANICS) 4 else it.severity * 8 }
        val grammar = clampScore(100 - grammarPenalty)

        val usedCount = usages.count { it.used }
        val patternProblems = usages.count { it.note != null && it.used }
        val wordUsage = if (task.targets.isEmpty()) {
            clampScore(100 - issues.count { it.category == IssueCategory.WORD_CHOICE } * 15)
        } else {
            clampScore(((usedCount.toDouble() / task.targets.size) * 100 - patternProblems * 8 - (if (listIssue != null) 30 else 0)).roundToInt())
        }

        val coherence = coherenceScore(clean, tokens, listIssue != null)
        val naturalnessPenalty = issues.filter { it.category == IssueCategory.COLLOCATION || it.category == IssueCategory.WORD_CHOICE }
            .sumOf { it.severity * 9 } + repetition.size * 6
        val naturalness = clampScore(100 - naturalnessPenalty)

        val content = tokens.filter { it !in functionWords }
        val diversity = if (content.isEmpty()) 0 else {
            val ttr = content.toSet().size.toDouble() / content.size
            clampScore((ttr * 110).roundToInt() - repetition.size * 5)
        }

        val lengthOk = wordCount >= task.minWords
        val targetsDone = if (task.targets.isEmpty()) 1.0 else usedCount.toDouble() / task.targets.size
        val taskCompletion = when {
            wordCount == 0 -> 0
            wordCount > task.maxWords -> 60
            task.requireAllTargets -> clampScore((targetsDone * 100).roundToInt() - if (lengthOk) 0 else 30)
            else -> clampScore(if (lengthOk) 100 else 60)
        }

        if (task.targets.isNotEmpty()) rationale += "Hedef kelimeler: $usedCount/${task.targets.size} kullanıldı."
        val missing = usages.filter { !it.used }.map { it.lemma }
        if (missing.isNotEmpty()) rationale += "Eksik kelimeler: ${missing.joinToString(", ")}."
        if (!lengthOk) rationale += "Cevap çok kısa (en az ${task.minWords} kelime bekleniyor)."
        rationale += "Gramer puanı, kurallarla bulunan ${issues.count { it.category == IssueCategory.GRAMMAR }} gramer ve ${issues.count { it.category == IssueCategory.MECHANICS }} yazım noktasına göre hesaplandı."
        rationale += "Tutarlılık puanı sezgiseldir (cümle yapısı, bağlaçlar, kelime listesi tespiti); anlamı kesin olarak ölçmez."

        val scores = ScoreBreakdown(grammar, wordUsage, coherence, naturalness, diversity, taskCompletion)
        val key = issues.sortedWith(compareByDescending<LanguageIssue> { it.certain }.thenByDescending { it.severity }.thenBy { it.start }).take(3)
        return Evaluation(
            text = clean,
            scores = scores,
            issues = issues,
            keyPoints = key,
            corrected = GrammarRules.applyCorrections(clean, issues.filter { it.start >= 0 }),
            targetUsage = usages,
            rationaleTr = rationale,
            wordCount = wordCount,
        )
    }

    /** Strips control characters and collapses whitespace; limits length (safe text handling). */
    fun sanitize(text: String, maxChars: Int = 4000): String =
        text.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "").replace(Regex("[ \\t]+"), " ").trim().take(maxChars)

    private fun patternNote(tokens: List<String>, idx: Int, target: TargetWord): String? {
        if (target.expectedPatterns.isEmpty()) return null
        val window = tokens.subList(idx + 1, min(tokens.size, idx + 4))
        val ok = target.expectedPatterns.any { p -> window.contains(p.lowercase()) }
        return if (ok) null else
            "\"${target.lemma}\" genellikle şu kalıplarla kullanılır: ${target.expectedPatterns.joinToString(" / ") { "${target.lemma} $it" }}. Cümlende bu kalıbı kontrol et (her zaman zorunlu değildir)."
    }

    /** Detects answers that only list the target words without building a sentence. */
    private fun wordListIssue(tokens: List<String>, targets: List<TargetWord>): LanguageIssue? {
        if (targets.size < 2 || tokens.isEmpty()) return null
        val targetForms = targets.flatMap { Morphology.forms(it.lemma.split(" ").first()) }.toSet()
        var run = 0
        var maxRun = 0
        tokens.forEach { t -> if (t in targetForms || t == "and") { run++; maxRun = max(maxRun, run) } else run = 0 }
        val functionRatio = tokens.count { it in functionWords }.toDouble() / tokens.size
        return if (maxRun >= 3 || (tokens.size >= 4 && functionRatio < 0.15)) {
            LanguageIssue("word_list", IssueCategory.COHERENCE, -1, -1, "", null,
                "Kelimeler bir cümle içinde anlamlı ilişki kurmadan art arda sıralanmış görünüyor. Her kelimeyi özne, fiil ve bağlamla kullan.", 3)
        } else null
    }

    private fun repetitionIssues(tokens: List<String>): List<LanguageIssue> =
        tokens.filter { it !in functionWords && it.length > 3 }
            .groupingBy { it }.eachCount()
            .filter { it.value >= 3 }
            .map { (w, c) ->
                LanguageIssue("repeat_$w", IssueCategory.REPETITION, -1, -1, w, null,
                    "\"$w\" kelimesi $c kez kullanılmış. Eş anlamlı bir kelime veya zamir kullanmak metni daha doğal yapar.", 1)
            }

    private fun coherenceScore(text: String, tokens: List<String>, isList: Boolean): Int {
        if (tokens.isEmpty()) return 0
        val sentences = Morphology.sentences(text)
        var score = 70
        val avgLen = tokens.size.toDouble() / max(1, sentences.size)
        if (avgLen in 5.0..30.0) score += 10 else score -= 10
        val functionRatio = tokens.count { it in functionWords }.toDouble() / tokens.size
        if (functionRatio in 0.25..0.7) score += 10 else score -= 15
        val connectives = setOf("because", "so", "but", "although", "however", "then", "when", "while", "eventually", "after", "before", "since", "therefore", "finally")
        if (tokens.any { it in connectives }) score += 10
        if (isList) score -= 40
        return clampScore(score)
    }

    private fun clampScore(v: Int) = v.coerceIn(0, 100)
}
