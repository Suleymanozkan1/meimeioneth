package com.lexiflow.data.repo

import com.lexiflow.core.active.Skill
import com.lexiflow.core.ai.EnrichRequest
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.competition.BattleMode
import com.lexiflow.core.competition.CompetitionRules
import com.lexiflow.core.competition.WordBank
import com.lexiflow.core.gamification.XpReason
import com.lexiflow.core.importer.DuplicateDetector
import com.lexiflow.core.language.Evaluation
import com.lexiflow.core.language.EvaluationTask
import com.lexiflow.core.language.Morphology
import com.lexiflow.core.language.ScoreBreakdown
import com.lexiflow.core.language.SentenceEvaluator
import com.lexiflow.core.language.TargetWord
import com.lexiflow.core.pronunciation.AsrHypothesis
import com.lexiflow.core.pronunciation.AudioSignals
import com.lexiflow.core.pronunciation.PronunciationResult
import com.lexiflow.core.pronunciation.PronunciationScorer
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.CompetitionEntity
import com.lexiflow.data.db.CompetitionSubmissionEntity
import com.lexiflow.data.db.ContentSourceEntity
import com.lexiflow.data.db.CulturalContextEntity
import com.lexiflow.data.db.PronunciationAttemptEntity
import com.lexiflow.data.db.SyncQueueEntity
import com.lexiflow.data.remote.BackendClient
import com.lexiflow.data.remote.ReportBody
import com.lexiflow.data.settings.SettingsRepository
import com.lexiflow.util.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.random.Random

class PronunciationRepository(
    private val db: AppDatabase,
    private val active: ActiveRecallRepository,
    private val clock: Clock,
) {
    fun observeRecent() = db.pronunciationDao().observeRecent()

    suspend fun score(target: String, hyps: List<AsrHypothesis>, audio: AudioSignals, cardId: Long?, mode: String): PronunciationResult {
        val result = PronunciationScorer.score(target, hyps, audio)
        db.pronunciationDao().insert(
            PronunciationAttemptEntity(cardId = cardId, target = target, heard = hyps.firstOrNull()?.text.orEmpty(), intelligibility = result.intelligibility,
                reliability = result.reliability.name, resultJson = LexiJson.encodeToString(PronunciationResult.serializer(), result), at = clock.now(), mode = mode),
        )
        val intel = result.intelligibility
        if (cardId != null && intel != null) {
            active.record(cardId, Skill.PRONUNCIATION, intel / 100.0, 0, mode, result.reliability.name, source = "APP")
        }
        return result
    }

    suspend fun deleteAll() = db.pronunciationDao().deleteAll()
}

/** Film / TV / music context with licence metadata, plus user-provided transcripts for personal study. */
class CultureRepository(
    private val db: AppDatabase,
    private val words: WordRepository,
    private val active: ActiveRecallRepository,
    private val clock: Clock,
) {
    private val dao = db.contentDao()

    fun observeSources() = dao.observeSources()

    suspend fun items(kinds: List<String>): List<Pair<CulturalContextEntity, ContentSourceEntity>> =
        dao.contextsForKinds(kinds).mapNotNull { c -> dao.source(c.sourceId)?.let { c to it } }

    suspend fun cardFor(context: CulturalContextEntity): Long? {
        val word = db.wordDao().byLemmaKey(context.targetLemmaKey).firstOrNull() ?: return null
        return db.cardDao().byWord(word.id).firstOrNull()?.id
    }

    suspend fun markShown(id: Long) = dao.markShown(id, clock.now())

    suspend fun recordCloze(context: CulturalContextEntity, correct: Boolean) {
        val card = cardFor(context) ?: return
        active.record(card, Skill.CONTEXT, if (correct) 1.0 else 0.0, 0, "CULTURE_CLOZE", if (correct) "CORRECT" else "WRONG", source = "CULTURE")
    }

    suspend fun recordProduction(context: CulturalContextEntity, text: String): Evaluation {
        val eval = SentenceEvaluator.evaluate(text, EvaluationTask(listOf(TargetWord(context.targetLemma)), minWords = 4))
        cardFor(context)?.let { card ->
            val score = if (eval.targetUsage.first().used) eval.scores.overall / 100.0 else 0.1
            active.record(card, Skill.PRODUCTION, score, 0, "CULTURE_PRODUCTION", if (score >= 0.7) "CORRECT" else "PARTIAL", answerText = text, source = "CULTURE")
        }
        return eval
    }

    /** Adds the target word to the personal wordbook when the learner does not have it yet. */
    suspend fun addToWordbook(context: CulturalContextEntity): Long {
        val deck = words.personalDeckId()
        return words.insertWord(deck, NewWord(context.targetLemma, senses = listOf(NewSense(context.meaningInContextTr, context.meaningInContextTr,
            examples = listOf(NewExample(context.excerpt.replace(" / ", " "), null, "user", context.answer))))), "USER", "culture:${context.id}")
    }

    /**
     * Imports a transcript/subtitle the learner owns or may use (e.g. .srt or pasted text). Lines containing
     * words from the learner's decks become short contexts (max 200 chars each). Nothing is uploaded or shared.
     */
    suspend fun importTranscript(title: String, kind: String, raw: String): Int {
        val lines = parseSubtitle(raw)
        val lemmas = words.lemmaMeaningIndex()
        val sourceId = dao.insertSource(ContentSourceEntity(kind = kind, title = title.ifBlank { "Kendi metnim" }.take(80), licenseStatus = "USER_PROVIDED_PERSONAL_USE",
            licenseNote = "Kullanıcının kendi sağladığı metin; yalnızca bu cihazda kişisel çalışma için saklanır, paylaşılmaz.", origin = "USER", createdAt = clock.now()))
        val contexts = mutableListOf<CulturalContextEntity>()
        for ((time, line) in lines) {
            if (line.length > 200) continue
            val tokens = Morphology.tokens(line)
            val hit = lemmas.keys.firstOrNull { key -> !key.contains(' ') && tokens.any { it in Morphology.forms(key) } } ?: continue
            val token = tokens.first { it in Morphology.forms(hit) }
            val answer = Regex("(?i)(?<![A-Za-z])" + Regex.escape(token) + "(?![A-Za-z])").find(line)?.value ?: continue
            contexts += CulturalContextEntity(sourceId = sourceId, excerpt = line, isQuote = true, location = time, targetLemma = hit,
                targetLemmaKey = DuplicateDetector.normalizeWord(hit), answer = answer, meaningInContextTr = lemmas[hit]?.firstOrNull().orEmpty(),
                explanationTr = "Kendi metninden alınan cümle. Kelimenin bu bağlamdaki anlamını kartındaki anlamlarla karşılaştır.",
                productionPromptTr = "\"$hit\" kelimesini benzer bir durumda kendi cümlende kullan.")
            if (contexts.size >= 100) break
        }
        if (contexts.isEmpty()) { dao.deleteSource(sourceId); return 0 }
        dao.insertContexts(contexts)
        return contexts.size
    }

    suspend fun deleteSource(id: Long) = dao.deleteSource(id)

    companion object {
        /** Parses SRT (index, "00:01:02,000 --> ...", text) or plain text into (timestamp, line). */
        fun parseSubtitle(raw: String): List<Pair<String?, String>> {
            val out = mutableListOf<Pair<String?, String>>()
            var time: String? = null
            raw.replace("\r", "").lines().forEach { l ->
                val line = l.trim()
                when {
                    line.isEmpty() -> time = null
                    line.all { it.isDigit() } -> Unit
                    line.contains("-->") -> time = line.substringBefore("-->").trim().substringBefore(',')
                    else -> out += time to line.replace(Regex("<[^>]+>"), "")
                }
            }
            return out.flatMap { (t, l) -> Morphology.sentences(l).ifEmpty { listOf(l) }.map { t to it } }
        }
    }
}

data class BotRound(val words: List<String>, val mode: BattleMode, val botText: String)

data class RoundOutcome(val mine: Evaluation, val bot: Evaluation?, val iWon: Boolean?)

/** Word battle: clearly-labelled bot practice offline, real online matches through the backend socket. */
class CompetitionRepository(
    private val db: AppDatabase,
    private val backend: BackendClient,
    private val gamification: GamificationRepository,
    private val clock: Clock,
) {
    fun observeRecent() = db.competitionDao().observeRecent()

    suspend fun bestScore() = db.competitionDao().bestScore()

    /** The bot is not a person: its text is assembled from LexiFlow's own example sentences. */
    suspend fun newBotRound(mode: BattleMode): BotRound {
        val words = WordBank.draw(mode.wordCount, Random(clock.now()))
        val sentences = words.mapNotNull { w ->
            db.wordDao().byLemmaKey(w).firstOrNull()?.let { db.wordDao().examplesForWord(it.id).randomOrNull() }?.en
        }
        return BotRound(words, mode, sentences.joinToString(" "))
    }

    suspend fun finishBotRound(round: BotRound, myText: String): RoundOutcome {
        val mine = CompetitionRules.score(myText, round.words, round.mode)
        val bot = CompetitionRules.score(round.botText, round.words, round.mode)
        val ranking = CompetitionRules.rank(listOf("me" to (mine to 0L), "bot" to (bot to 1L)))
        saveResult(round.mode, "BOT_PRACTICE", null, round.words, listOf(Triple("Ben", true, mine), Triple("LexiBot (bot)", false, bot)), ranking.indexOf("me") + 1)
        return RoundOutcome(mine, bot, ranking.first() == "me")
    }

    suspend fun saveResult(mode: BattleMode, kind: String, roomId: String?, words: List<String>, players: List<Triple<String, Boolean, Evaluation>>, myRank: Int?) {
        val id = db.competitionDao().insert(CompetitionEntity(mode = mode.name, kind = kind, roomId = roomId,
            wordsJson = LexiJson.encodeToString(ListSerializer(String.serializer()), words), at = clock.now(), rank = myRank, players = players.size))
        db.competitionDao().insertSubmissions(players.map { (label, me, e) ->
            CompetitionSubmissionEntity(competitionId = id, playerLabel = label, isMe = me, text = e.text, overall = e.scores.overall,
                breakdownJson = LexiJson.encodeToString(ScoreBreakdown.serializer(), e.scores), corrected = e.corrected)
        })
        gamification.award(XpReason.COMPETITION_ROUND, id)
    }

    /** Moderation reports are queued so they are not lost when offline. */
    suspend fun report(roomId: String, playerId: String, reason: String) {
        db.syncDao().enqueue(SyncQueueEntity(kind = "REPORT", payloadJson = LexiJson.encodeToString(ReportBody.serializer(), ReportBody(roomId, playerId, reason.take(300))), createdAt = clock.now()))
    }

    suspend fun leaderboard(mode: BattleMode) = backend.leaderboard(mode.name)
}

/** Data retention and privacy controls. */
class MaintenanceRepository(private val db: AppDatabase, private val settings: SettingsRepository, private val clock: Clock) {
    suspend fun applyRetention() {
        val s = settings.current()
        val day = 86_400_000L
        if (s.keepSpeakingDays > 0) db.speakingDao().deleteOlderThan(clock.now() - s.keepSpeakingDays * day)
        if (s.keepPronunciationDays > 0) db.pronunciationDao().deleteOlderThan(clock.now() - s.keepPronunciationDays * day)
    }

    suspend fun deleteConversations() = db.speakingDao().deleteAll()
    suspend fun deletePronunciation() = db.pronunciationDao().deleteAll()
    suspend fun deleteAnswerTexts() = db.practiceDao().clearAnswerTexts()
}

/** AI enrichment: senses, collocations and 10+ example sentences for imported words (needs backend). */
class EnrichmentRepository(private val db: AppDatabase, private val words: WordRepository, private val backend: BackendClient, private val settings: SettingsRepository) {
    suspend fun enrich(wordId: Long): Int {
        val w = db.wordDao().get(wordId) ?: return 0
        val senses = db.wordDao().senses(wordId)
        return apply(wordId, backend.enrich(EnrichRequest(w.lemma, senses.firstOrNull()?.meaningTr.orEmpty(), settings.current().cefr)))
    }

    /** Asks the server for a word that is not in the app yet (quick add without a meaning). */
    suspend fun draft(lemma: String): com.lexiflow.core.ai.EnrichResponse = backend.enrich(EnrichRequest(lemma, "", settings.current().cefr))

    /** Merges a server answer into the word: new senses and examples, and empty rich fields. Returns new examples. */
    suspend fun apply(wordId: Long, r: com.lexiflow.core.ai.EnrichResponse): Int {
        val w = db.wordDao().get(wordId) ?: return 0
        val senses = db.wordDao().senses(wordId)
        var added = 0
        r.senses.forEach { draft ->
            val match = senses.firstOrNull { DuplicateDetector.normalizeMeaning(it.meaningTr) == DuplicateDetector.normalizeMeaning(draft.meaningTr) }
            val senseId = match?.id ?: words.addSense(wordId, NewSense(draft.label, draft.meaningTr, draft.definitionEn), origin = "AI")
            val fresh = draft.examples.filter { it.answer.isNotBlank() && it.en.contains(it.answer) }
                .map { NewExample(it.en, it.tr.ifBlank { null }, it.context, it.answer, it.acceptedAlternatives, it.noteTr) }
            added += words.addExamples(senseId, fresh, "AI")
        }
        val rich = WordRepository.rich(w.richJson)
        val merged = rich.copy(
            collocations = (rich.collocations + r.collocations).distinct(),
            phrasalVerbs = (rich.phrasalVerbs + r.phrasalVerbs).distinct(),
            wordFamily = (rich.wordFamily + r.wordFamily).distinct(),
            synonyms = (rich.synonyms + r.synonyms).distinct(),
            antonyms = (rich.antonyms + r.antonyms).distinct(),
            commonMistakesTr = (rich.commonMistakesTr + r.commonMistakesTr).distinct(),
            grammarNoteTr = rich.grammarNoteTr ?: r.grammarNoteTr,
        )
        words.updateWord(w.copy(pos = w.pos ?: r.pos, ipaUk = w.ipaUk ?: r.ipaUk, ipaUs = w.ipaUs ?: r.ipaUs, register = w.register ?: r.register,
            richJson = LexiJson.encodeToString(com.lexiflow.data.content.RichInfo.serializer(), merged)))
        return added
    }
}
