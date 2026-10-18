package com.lexiflow.core.story

import com.lexiflow.core.ai.QuestionType
import com.lexiflow.core.ai.StoryQuestion
import com.lexiflow.core.ai.StoryResponse
import com.lexiflow.core.ai.TargetOccurrence
import com.lexiflow.core.exercise.ClozeBuilder
import com.lexiflow.core.exercise.Distractors
import com.lexiflow.core.language.Morphology
import kotlin.math.max
import kotlin.random.Random

/** Signals used to decide which words a forgetting-curve story should revive. */
data class StoryCandidate(
    val cardId: Long,
    val lemma: String,
    val meaningTr: String,
    /** Current FSRS retrievability, null for never-reviewed cards (excluded). */
    val retrievability: Double?,
    val dueAt: Long,
    val lastReviewAt: Long?,
    val lapses: Int,
    val difficulty: Double,
    val productionMastery: Double,
    val flaggedCantUse: Boolean,
    val example: String?,
    val exampleAnswer: String?,
)

/**
 * Chooses target words for personalised stories.
 * Priority = forgetting risk (1 - R) x 2 + overdue + production gap + lapses + "can't use" flag.
 * Cards reviewed in the last 6 hours are skipped so the story is a spaced encounter, not a repeat.
 * Reading a story never writes an FSRS review (no double counting); story exercises update the separate
 * active-practice skills only. See docs/ACTIVE_RECALL.md.
 */
object StoryPlanner {
    const val RECENT_MS = 6 * 3_600_000L

    fun priority(c: StoryCandidate, now: Long): Double {
        val r = c.retrievability ?: return Double.NEGATIVE_INFINITY
        val overdueDays = max(0.0, (now - c.dueAt) / 86_400_000.0)
        return (1 - r) * 2.0 + minOf(overdueDays, 14.0) * 0.1 + (1 - c.productionMastery) +
            minOf(c.lapses, 5) * 0.1 + (c.difficulty / 10.0) * 0.3 + if (c.flaggedCantUse) 1.0 else 0.0
    }

    fun select(candidates: List<StoryCandidate>, count: Int, now: Long): List<StoryCandidate> =
        candidates
            .filter { it.retrievability != null }
            .filter { it.lastReviewAt == null || now - it.lastReviewAt > RECENT_MS }
            .sortedByDescending { priority(it, now) }
            .distinctBy { it.lemma.lowercase() }
            .take(count)

    /** A story is recommended when several words are at risk of being forgotten or not used actively. */
    fun shouldRecommend(candidates: List<StoryCandidate>, now: Long): Boolean =
        candidates.count { (it.retrievability ?: 1.0) < 0.85 || it.productionMastery < 0.3 || it.flaggedCantUse } >= 3 &&
            select(candidates, 3, now).size >= 3
}

data class ComposerTarget(val lemma: String, val meaningTr: String, val example: String, val answer: String)

/**
 * Offline fallback when no AI backend is configured. It is explicitly labelled as a template story in the UI:
 * it weaves the learner's own (human-written) example sentences into a themed narrative frame. It does not
 * pretend to be AI-generated.
 */
object OfflineStoryComposer {

    private data class Frame(val title: String, val opening: String, val bridges: List<String>, val closing: String)

    private val frames: Map<String, Frame> = mapOf(
        "Daily life" to Frame("An Ordinary Tuesday",
            "Deniz woke up early on Tuesday, made a strong coffee and looked at the long list on the fridge.",
            listOf("By lunchtime, the day had already become busier than expected.", "On the way home, she called her brother.", "In the evening, the neighbours knocked on the door.", "Later, she sat down and thought about the day."),
            "Before going to sleep, Deniz smiled. It had been an ordinary Tuesday, but she had learned something new."),
        "Travel" to Frame("The Missed Connection",
            "Mert arrived at the airport two hours early, but the screen above the gate showed a problem.",
            listOf("At the information desk, a tired officer explained the situation.", "While he waited, he started talking to a woman from Lisbon.", "The next morning, the city looked completely different.", "On the last day of the trip, he wrote in his notebook."),
            "On the plane home, Mert realised that the delay had given him the best part of the journey."),
        "Work" to Frame("The New Project",
            "On her first day as team leader, Selin found a thick folder on her desk and a note from her manager.",
            listOf("During the first meeting, everyone had a different opinion.", "A week later, the numbers started to change.", "Her colleague Ali stopped by her desk with a question.", "Before the final presentation, the team stayed late."),
            "When the project finally ended, Selin knew the team was stronger than when they started."),
        "Relationships" to Frame("The Old Friend",
            "Can had not spoken to Ece for three years, until a message appeared on his phone one rainy evening.",
            listOf("They agreed to meet at their old café.", "At first, the conversation felt a little strange.", "After a while, they started laughing about school.", "Walking home, Can kept thinking about what she had said."),
            "Some friendships pause, Can thought, but they do not really end."),
        "Technology" to Frame("The Smart Home",
            "When Burak installed a new smart-home system, he expected his life to become easier.",
            listOf("On the second day, the lights started acting strangely.", "He called the support line, and a calm voice answered.", "That weekend, his grandmother came to visit.", "By the end of the month, he had changed his mind about many things."),
            "Technology, Burak decided, is only as smart as the people who use it."),
        "Mystery" to Frame("The Locked Room",
            "Nobody in the building could explain why the door of room 12 had been locked from the inside.",
            listOf("Detective Arslan arrived just after midnight.", "The caretaker told a story that did not quite make sense.", "In the morning, a small detail changed everything.", "Finally, the detective called everyone into the hall."),
            "The case was closed, but the building never felt quite the same again."),
        "Adventure" to Frame("Above the Clouds",
            "Zeynep and her cousin started climbing the mountain before sunrise, carrying more than they needed.",
            listOf("Halfway up, the weather began to change.", "They found shelter behind a large rock.", "When the wind finally stopped, the view was incredible.", "On the way down, they met a shepherd and his dog."),
            "At the bottom, tired but happy, they promised to come back next summer."),
        "Business" to Frame("The Small Bakery",
            "Elif's bakery was small, but every morning there was a queue outside the door.",
            listOf("One day, a big supermarket opened across the street.", "She spent the evening looking at her accounts.", "A customer gave her an unexpected idea.", "Three months later, the local newspaper wrote about her."),
            "The queue outside the bakery was longer than ever."),
        "Science" to Frame("The Unexpected Result",
            "In the university lab, Dr. Kaya and her students were repeating the same experiment for the fifth time.",
            listOf("Then, one sample behaved in a way nobody had predicted.", "They checked every instrument carefully.", "A visiting professor looked at the data with interest.", "Months later, they finally understood what had happened."),
            "Sometimes, Dr. Kaya told her students, a mistake is the beginning of a discovery."),
        "Everyday conversations" to Frame("At the Corner Shop",
            "Every evening, Mr. Demir chatted with whoever came into his corner shop.",
            listOf("A young student came in looking worried.", "Next, an old customer asked about the football results.", "Just before closing, a tourist asked for directions.", "When the shop was quiet, Mr. Demir talked to his daughter on the phone."),
            "He turned off the lights, thinking that a small shop can hold a lot of stories."),
    )

    val themes: List<String> get() = frames.keys.toList()

    fun compose(theme: String, targets: List<ComposerTarget>, seed: Long = 0L): StoryResponse {
        val frame = frames[theme] ?: frames.getValue("Daily life")
        val paragraphs = mutableListOf(frame.opening)
        targets.forEachIndexed { i, t ->
            val bridge = frame.bridges[i % frame.bridges.size]
            paragraphs += "$bridge ${t.example}"
        }
        paragraphs += frame.closing
        val questions = buildQuestions(targets, seed)
        val body = paragraphs.joinToString("\n")
        return StoryResponse(
            title = frame.title,
            paragraphs = paragraphs,
            targetOccurrences = targets.map { t -> TargetOccurrence(t.lemma, countUses(body, t.lemma)) },
            questions = questions,
            grammarNoteTr = "Bu çevrim dışı şablon hikâyedir: senin kartlarındaki örnek cümleler bir anlatı çerçevesine yerleştirildi. AI bağlantısı açıldığında tamamen özgün hikâyeler üretilir.",
        )
    }

    fun countUses(text: String, lemma: String): Int {
        val forms = Morphology.forms(lemma)
        return Morphology.tokens(text).count { it in forms }
    }

    /** Standard post-story tasks: meaning in context, cloze, TR->EN recall, own sentence, retell. */
    fun buildQuestions(targets: List<ComposerTarget>, seed: Long): List<StoryQuestion> {
        val random = Random(seed)
        val out = mutableListOf<StoryQuestion>()
        val meanings = targets.map { it.meaningTr }
        targets.forEachIndexed { i, t ->
            val (options, idx) = Distractors.options(t.meaningTr, meanings + FALLBACK_MEANINGS, 4, seed + i)
            out += StoryQuestion(QuestionType.MEANING_IN_CONTEXT, "Hikâyede \"${t.lemma}\" hangi anlamda kullanıldı?", options, idx, targetLemma = t.lemma)
            ClozeBuilder.build(t.example, t.answer)?.let { c ->
                out += StoryQuestion(QuestionType.CLOZE, c.prompt, answer = c.answer, targetLemma = t.lemma)
            }
        }
        targets.shuffled(random).take(2).forEach { t ->
            out += StoryQuestion(QuestionType.OPEN_SENTENCE, "\"${t.meaningTr}\" anlamına gelen kelimeyi hatırla ve kendi hayatınla ilgili bir cümle kur.", answer = t.lemma, targetLemma = t.lemma)
        }
        out += StoryQuestion(QuestionType.RETELL, "Hikâyeyi 3-4 cümleyle kendi kelimelerinle anlat. En az iki hedef kelimeyi kullanmaya çalış.")
        return out
    }

    private val FALLBACK_MEANINGS = listOf("vazgeçmek", "hızlandırmak", "kutlamak", "şüphelenmek", "paylaşmak", "tahmin etmek")
}
