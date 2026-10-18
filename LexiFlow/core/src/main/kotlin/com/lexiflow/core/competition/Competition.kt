package com.lexiflow.core.competition

import com.lexiflow.core.language.Evaluation
import com.lexiflow.core.language.EvaluationTask
import com.lexiflow.core.language.LanguageIssue
import com.lexiflow.core.language.ScoreBreakdown
import com.lexiflow.core.language.SentenceEvaluator
import com.lexiflow.core.language.TargetWord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
enum class BattleMode(val labelTr: String, val roundSeconds: Int, val minWords: Int, val wordCount: Int) {
    FIVE_WORD_SENTENCE("5 kelimeyle cümle", 120, 8, 5),
    FIVE_WORD_STORY("5 kelimeyle kısa hikâye", 240, 30, 5),
    SAME_TOPIC("Aynı konu, farklı kelimeler", 180, 20, 3),
    TIMED_WRITING("Süreli yazma", 90, 15, 3),
}

/** Shared, deterministic competition rules. The server uses exactly this to score submissions. */
object CompetitionRules {
    const val MAX_CHARS = 1500

    fun task(words: List<String>, mode: BattleMode) = EvaluationTask(
        targets = words.map { TargetWord(it, WordBank.patterns[it].orEmpty()) },
        requireAllTargets = true,
        minWords = mode.minWords,
        maxWords = 300,
    )

    fun score(text: String, words: List<String>, mode: BattleMode): Evaluation =
        SentenceEvaluator.evaluate(text.take(MAX_CHARS), task(words, mode))

    /** Highest overall wins; ties broken by task completion, then grammar, then earlier submission. */
    fun rank(results: List<Pair<String, Pair<Evaluation, Long>>>): List<String> =
        results.sortedWith(
            compareByDescending<Pair<String, Pair<Evaluation, Long>>> { it.second.first.scores.overall }
                .thenByDescending { it.second.first.scores.taskCompletion }
                .thenByDescending { it.second.first.scores.grammar }
                .thenBy { it.second.second },
        ).map { it.first }
}

/** Original word list (authored for LexiFlow) used to draw competition words. */
object WordBank {
    val b1b2: List<String> = listOf(
        "reluctant", "efficient", "overlook", "eventually", "approach", "rely", "achieve", "afford", "anxious", "appreciate",
        "assume", "aware", "challenge", "confident", "consider", "convince", "decline", "deserve", "determine", "encourage",
        "estimate", "expand", "familiar", "focus", "gradually", "ignore", "improve", "influence", "insist", "maintain",
        "manage", "obvious", "opportunity", "persuade", "postpone", "prevent", "priority", "recommend", "reduce", "regret",
        "require", "resolve", "responsible", "significant", "struggle", "suggest", "support", "surprise", "tend", "willing",
    )

    val patterns: Map<String, List<String>> = mapOf(
        "reluctant" to listOf("to", "about"), "rely" to listOf("on"), "insist" to listOf("on", "that"),
        "aware" to listOf("of", "that"), "responsible" to listOf("for"), "familiar" to listOf("with", "to"),
        "focus" to listOf("on"), "afford" to listOf("to"), "willing" to listOf("to"), "tend" to listOf("to"),
        "anxious" to listOf("about", "to"), "struggle" to listOf("with", "to"), "convince" to listOf("to", "that", "of"),
        "persuade" to listOf("to", "that"), "prevent" to listOf("from"), "encourage" to listOf("to"),
    )

    fun draw(count: Int, random: Random = Random.Default): List<String> = b1b2.shuffled(random).take(count)
}

@Serializable
data class PlayerResult(
    val playerId: String,
    val displayName: String,
    val text: String,
    val scores: ScoreBreakdown,
    val overall: Int,
    val corrected: String,
    val keyPoints: List<LanguageIssue>,
    val rationaleTr: List<String>,
    val submitted: Boolean,
)

/** Messages sent by the app over the competition WebSocket. */
@Serializable
sealed class ClientMessage {
    @Serializable @SerialName("join_queue") data class JoinQueue(val mode: BattleMode) : ClientMessage()
    @Serializable @SerialName("leave_queue") data object LeaveQueue : ClientMessage()
    @Serializable @SerialName("submit") data class Submit(val roomId: String, val text: String) : ClientMessage()
    @Serializable @SerialName("rejoin") data class Rejoin(val roomId: String) : ClientMessage()
    @Serializable @SerialName("report") data class Report(val roomId: String, val playerId: String, val reason: String) : ClientMessage()
    @Serializable @SerialName("ping") data object Ping : ClientMessage()
}

/** Messages sent by the server. */
@Serializable
sealed class ServerMessage {
    @Serializable @SerialName("welcome") data class Welcome(val playerId: String, val displayName: String, val onlinePlayers: Int) : ServerMessage()
    @Serializable @SerialName("queued") data class Queued(val mode: BattleMode, val waiting: Int) : ServerMessage()
    @Serializable @SerialName("match_found") data class MatchFound(
        val roomId: String,
        val mode: BattleMode,
        val words: List<String>,
        val roundSeconds: Int,
        val endsAtEpochMs: Long,
        val players: List<String>,
    ) : ServerMessage()
    @Serializable @SerialName("submission_ack") data class SubmissionAck(val roomId: String) : ServerMessage()
    @Serializable @SerialName("player_left") data class PlayerLeft(val roomId: String, val displayName: String) : ServerMessage()
    @Serializable @SerialName("round_result") data class RoundResult(
        val roomId: String,
        val words: List<String>,
        val results: List<PlayerResult>,
        val ranking: List<String>,
    ) : ServerMessage()
    @Serializable @SerialName("error") data class Error(val code: String, val message: String) : ServerMessage()
    @Serializable @SerialName("pong") data object Pong : ServerMessage()
}
