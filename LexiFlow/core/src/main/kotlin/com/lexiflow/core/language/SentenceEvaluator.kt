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

        // Strings that are not English words ("bdnd", "asdfgh", Turkish words) cannot earn grammar or style points.
        val notWords = nonWordIssues(clean)
        issues += notWords
        // Counted on Unicode words: the ASCII tokenizer splits "türkçe" into fragments.
        val allWords = Regex("[\\p{L}]+(?:'[\\p{L}]+)?").findAll(clean).count()
        val nonWordRatio = if (allWords == 0) 0.0 else min(1.0, notWords.size.toDouble() / allWords)

        val listIssue = wordListIssue(tokens, task.targets)
        if (listIssue != null) issues += listIssue
        val repetition = repetitionIssues(tokens)
        issues += repetition

        // Scores.
        val grammarPenalty = issues.filter { it.category == IssueCategory.GRAMMAR || it.category == IssueCategory.MECHANICS }
            .sumOf { if (it.category == IssueCategory.MECHANICS) 4 else it.severity * 8 }
        // A one-word answer has no grammar to judge: style scores grow with length up to the expected minimum.
        val lengthFactor = if (task.minWords <= 0) 1.0 else min(1.0, wordCount.toDouble() / task.minWords)
        // Mostly non-English text is not judged at all.
        val quality = if (nonWordRatio >= 0.5) 0.0 else lengthFactor * (1.0 - nonWordRatio)
        fun scaled(v: Int) = clampScore((v * quality).roundToInt())
        val grammar = scaled(100 - grammarPenalty)

        val usedCount = usages.count { it.used }
        val patternProblems = usages.count { it.note != null && it.used }
        val wordUsage = if (task.targets.isEmpty()) {
            scaled(100 - issues.count { it.category == IssueCategory.WORD_CHOICE } * 15)
        } else {
            clampScore(((usedCount.toDouble() / task.targets.size) * 100 - patternProblems * 8 - (if (listIssue != null) 30 else 0)).roundToInt())
        }

        val wordOrder = issues.any { it.ruleId == GrammarRules.WORD_ORDER }
        val coherence = scaled(coherenceScore(clean, tokens, listIssue != null) - if (wordOrder) 20 else 0)
        val naturalnessPenalty = issues.filter { it.category == IssueCategory.COLLOCATION || it.category == IssueCategory.WORD_CHOICE || it.ruleId == GrammarRules.WORD_ORDER }
            .sumOf { it.severity * 9 } + repetition.size * 6
        val naturalness = scaled(100 - naturalnessPenalty)

        val content = tokens.filter { it !in functionWords }
        val diversity = if (content.isEmpty()) 0 else {
            val ttr = content.toSet().size.toDouble() / content.size
            scaled((ttr * 110).roundToInt() - repetition.size * 5)
        }

        val lengthOk = wordCount >= task.minWords
        val targetsDone = if (task.targets.isEmpty()) 1.0 else usedCount.toDouble() / task.targets.size
        val taskBase = when {
            wordCount == 0 -> 0
            wordCount > task.maxWords -> 60
            task.requireAllTargets -> clampScore((targetsDone * 100).roundToInt() - if (lengthOk) 0 else 30)
            else -> clampScore(if (lengthOk) 100 else 60)
        }
        val taskCompletion = (taskBase * (1.0 - nonWordRatio)).roundToInt()

        if (task.targets.isNotEmpty()) rationale += "Hedef kelimeler: $usedCount/${task.targets.size} kullanıldı."
        val missing = usages.filter { !it.used }.map { it.lemma }
        if (missing.isNotEmpty()) rationale += "Eksik kelimeler: ${missing.joinToString(", ")}."
        if (!lengthOk) rationale += "Cevap çok kısa (en az ${task.minWords} kelime bekleniyor); kısa cevapta gramer ve doğallık puanı da düşük tutulur."
        if (notWords.isNotEmpty()) rationale += "${notWords.size} ifade İngilizce bir kelimeye benzemiyor; bu yüzden puanlar düşürüldü."
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

    private val turkishLetters = Regex("[çğıöşüÇĞİÖŞÜ]")
    private val shapeAllowList = setOf("hmm", "shh", "psst", "brr", "pfft", "tsk", "nth", "mr", "mrs", "ms", "dr", "st", "vs", "tv", "pc", "cd", "dvd", "mp", "bbq", "gym", "gyms", "myth", "myths", "rhythm", "rhythms", "lynx", "sync", "hymn", "hymns", "crypt", "lynch", "nymph", "nymphs", "tryst", "pyx", "why", "my", "by", "fly", "sky", "dry", "try", "cry", "shy", "spy", "fry", "ply", "sly", "pry", "wry", "thy", "gypsy")

    /**
     * Words that cannot be English: no vowel at all ("bdnd"), the same letter three times ("aaah" excluded),
     * five consonants in a row, "q" not followed by "u", or lowercase words with Turkish letters. Acronyms written
     * in capitals and capitalised Turkish names are allowed. A word list would be stricter but the core module must stay small and run on the server too.
     */
    fun looksLikeNonWord(word: String): Boolean {
        // Turkish names ("Ayşe", "Kadıköy") are fine in an English sentence; lowercase Turkish words are not English.
        if (turkishLetters.containsMatchIn(word)) return !word.first().isUpperCase()
        val letters = word.filter { it.isLetter() }
        if (letters.length < 2) return false
        if (letters.length <= 5 && letters.all { it.isUpperCase() }) return false
        val w = letters.lowercase()
        if (w in shapeAllowList) return false
        if (w.none { it in "aeiouy" }) return true
        if (Regex("([a-z])\\1\\1").containsMatchIn(w) && !Regex("^(a+h+|h+m+|o+h+|z+|s+h+|w+o+w+|y+a+y+)$").matches(w)) return true
        if (Regex("[bcdfghjklmnpqrstvwxz]{5,}").containsMatchIn(w) && !w.contains("ngth") && !w.contains("tchw")) return true
        if (Regex("q(?!u)[a-z]").containsMatchIn(w) && w !in setOf("qatar", "qi", "faqs", "iraqi", "qwerty")) return true
        return false
    }

    private fun nonWordIssues(text: String): List<LanguageIssue> =
        Regex("[\\p{L}]+(?:'[\\p{L}]+)?").findAll(text).filter { looksLikeNonWord(it.value) }.map { m ->
            val turkish = turkishLetters.containsMatchIn(m.value)
            LanguageIssue("not_a_word", IssueCategory.WORD_CHOICE, m.range.first, m.range.last + 1, m.value, null,
                if (turkish) "\"${m.value}\" Türkçe bir kelime gibi görünüyor; cümleyi İngilizce yaz."
                else "\"${m.value}\" İngilizce bir kelimeye benzemiyor. Yazım hatası mı? Gerçek bir cümle kur.", 3)
        }.toList()

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
