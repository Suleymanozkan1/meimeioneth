package com.lexiflow.data.repo

import com.lexiflow.core.active.Skill
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.gamification.XpReason
import com.lexiflow.core.language.GrammarDrills
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.CardDetail
import com.lexiflow.data.db.DailyChallengeEntity
import com.lexiflow.domain.Exercise
import com.lexiflow.domain.ExerciseBuilder
import com.lexiflow.domain.ExerciseType
import com.lexiflow.util.Clock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Drill-based learning modes. Each has its own learning goal (see docs/MODES.md). */
enum class StudyMode(val titleTr: String, val goalTr: String) {
    TR_TO_EN("Türkçeden İngilizceye", "Türkçe anlamdan İngilizce kelimeyi kendin üret (aktif hatırlama)."),
    EN_TO_TR("İngilizceden Türkçeye", "Kelimeyi görünce anlamını yardımsız hatırla."),
    CLOZE("Boşluk Doldurma", "Kelimeyi gerçek cümle bağlamında, azalan ipuçlarıyla bul."),
    SENTENCE_BUILDER("Cümle Kurma", "Doğru söz dizimini kurarak kalıpları içselleştir."),
    WORD_PRODUCTION("Kelime Üretimi", "Kelimeyi kendi cümlende, ipucu olmadan kullan."),
    TRANSLATION("Çeviri Pratiği", "Türkçe cümleyi hedef kelimeyle İngilizceye aktar."),
    CONTEXT_CHALLENGE("Bağlam Meydan Okuması", "İngilizce bir tanıma/duruma uygun kelimeyi seç."),
    LISTENING("Dinleme", "Kelimeyi konuşma içinde kulaktan tanı."),
    DICTATION("Dikte", "Duyduğun cümleyi eksiksiz yazarak ses-yazı bağını güçlendir."),
    GRAMMAR("Gramer Antrenörü", "Türk öğrencilerin sık yaptığı hataları bul ve düzelt."),
    ACTIVE_PATH("Aktif Üretim Yolu", "\"Tanıyorum ama kullanamıyorum\" kelimelerini adım adım aktif kullanıma taşı."),
    WEAK_WORDS("Zayıf Kelimeler", "Sık unutulan ve aktif kullanımı zayıf kelimeleri hedefle."),
    MIXED_REVIEW("Karışık Tekrar", "Farklı alıştırma türlerini karıştırarak esnek hatırlama."),
    EXAM("Sınav Pratiği", "Süreli, ipucusuz 20 soruyla gerçek seviyeni ölç."),
    DAILY_CHALLENGE("Günlük Görev", "Bugünün 5 kelimesiyle kısa, tamamlanabilir bir görev."),
}

data class DrillSession(val mode: StudyMode, val exercises: List<Exercise>, val emptyReasonTr: String? = null, val timeLimitSec: Int? = null)

class SessionRepository(
    private val db: AppDatabase,
    private val active: ActiveRecallRepository,
    private val gamification: GamificationRepository,
    private val clock: Clock,
) {
    private val cards = db.cardDao()
    private val words = db.wordDao()

    suspend fun build(mode: StudyMode, size: Int = 10): DrillSession {
        val seed = clock.now()
        if (mode == StudyMode.GRAMMAR) {
            val drills = GrammarDrills.all.shuffled(kotlin.random.Random(seed)).take(size)
            return DrillSession(mode, drills.map { ExerciseBuilder.grammarDrill(it) })
        }
        val pairs: List<Pair<CardDetail, ExerciseType>> = when (mode) {
            StudyMode.ACTIVE_PATH -> active.productionQueue(size, onlyDue = false).map { it.card to typeForStage(it.stage, it.states.getValue(Skill.CONTEXT).hintLevel) }
            StudyMode.WEAK_WORDS -> active.weakWords(size / 2 + 1).flatMap { listOf(it.card to ExerciseType.TR_TO_EN, it.card to clozeType(it.states.getValue(Skill.CONTEXT).hintLevel)) }
            StudyMode.DAILY_CHALLENGE -> dailyChallengeCards().let { list ->
                list.map { it to ExerciseType.TR_TO_EN } + list.map { it to ExerciseType.CLOZE_TYPED } + list.take(2).map { it to ExerciseType.PRODUCTION }
            }
            StudyMode.MIXED_REVIEW, StudyMode.EXAM -> {
                val pool = candidates(if (mode == StudyMode.EXAM) 20 else size)
                val types = listOf(ExerciseType.EN_TO_TR, ExerciseType.TR_TO_EN, ExerciseType.CLOZE_TYPED, ExerciseType.CONTEXT_CHOICE,
                    ExerciseType.CLOZE_CHOICE, ExerciseType.TRANSLATION, ExerciseType.LISTENING_CHOICE)
                pool.mapIndexed { i, c -> c to types[i % types.size] }
            }
            else -> candidates(size).map { it to defaultType(mode, it) }
        }
        if (pairs.isEmpty()) {
            val reason = when (mode) {
                StudyMode.ACTIVE_PATH -> "Aktif üretim kuyruğun boş. Tekrar ekranında \"Tanıyorum ama kullanamıyorum\" seçeneğini kullan veya önce birkaç kartı tekrar et."
                StudyMode.WEAK_WORDS -> "Şu an zayıf kelimen yok. Tekrar ettikçe zorlandığın kelimeler burada görünecek."
                else -> "Henüz kelime yok. Önce bir deste ekle veya içe aktar."
            }
            return DrillSession(mode, emptyList(), reason)
        }
        val meaningPool = words.randomMeanings(30)
        val lemmaPool = words.randomLemmas(30)
        val exercises = pairs.mapIndexedNotNull { i, (card, type) ->
            val examples = words.examplesForWord(card.card.wordId).filter { it.senseId == card.card.senseId }.ifEmpty { words.examplesForWord(card.card.wordId) }
            val example = ExerciseBuilder.pickExample(examples, seed + i)
            val hint = if (mode == StudyMode.EXAM) 0 else active.state(card.card.id, type.skill).hintLevel
            val ex = ExerciseBuilder.build(type, card, example, hint, meaningPool, lemmaPool, seed + i)
                ?: ExerciseBuilder.build(ExerciseType.TR_TO_EN, card, example, hint, meaningPool, lemmaPool, seed + i)
            if (ex?.exampleId != null) words.markShown(ex.exampleId, clock.now())
            ex
        }
        return DrillSession(mode, exercises.take(if (mode == StudyMode.DAILY_CHALLENGE) 12 else maxOf(size, 20)), timeLimitSec = if (mode == StudyMode.EXAM) 600 else null)
    }

    private fun clozeType(hintLevel: Int) = when {
        hintLevel >= 2 -> ExerciseType.CLOZE_CHOICE
        hintLevel == 1 -> ExerciseType.CLOZE_HINT
        else -> ExerciseType.CLOZE_TYPED
    }

    private fun typeForStage(stage: ActiveStage, contextHint: Int) = when (stage) {
        ActiveStage.RECALL -> ExerciseType.TR_TO_EN
        ActiveStage.CLOZE -> clozeType(contextHint)
        ActiveStage.SENTENCE, ActiveStage.SPEAKING -> ExerciseType.PRODUCTION
        ActiveStage.RETEST, ActiveStage.MASTERED -> ExerciseType.TRANSLATION
    }

    private suspend fun defaultType(mode: StudyMode, card: CardDetail): ExerciseType = when (mode) {
        StudyMode.TR_TO_EN -> ExerciseType.TR_TO_EN
        StudyMode.EN_TO_TR -> ExerciseType.EN_TO_TR
        StudyMode.CLOZE -> clozeType(active.state(card.card.id, Skill.CONTEXT).hintLevel)
        StudyMode.SENTENCE_BUILDER -> ExerciseType.SENTENCE_ORDER
        StudyMode.WORD_PRODUCTION -> ExerciseType.PRODUCTION
        StudyMode.TRANSLATION -> ExerciseType.TRANSLATION
        StudyMode.CONTEXT_CHALLENGE -> ExerciseType.CONTEXT_CHOICE
        StudyMode.LISTENING -> ExerciseType.LISTENING_CHOICE
        StudyMode.DICTATION -> ExerciseType.DICTATION
        else -> ExerciseType.TR_TO_EN
    }

    /** Studied cards first (least practiced first), topped up with new cards so drills work from day one. */
    private suspend fun candidates(size: Int): List<CardDetail> {
        val studied = cards.randomStudied(size * 3)
        val masteries = studied.associate { c -> c.card.id to active.states(c.card.id).values.minOf { it.mastery } }
        val ranked = studied.sortedBy { masteries[it.card.id] }.take(size)
        if (ranked.size >= size) return ranked.shuffled()
        return (ranked + cards.newCards(size - ranked.size)).distinctBy { it.card.id }
    }

    suspend fun dailyChallengeCards(): List<CardDetail> {
        val date = clock.localDate(clock.now())
        val existing = db.gamificationDao().challenge(date)
        val ids = existing?.let { runCatching { LexiJson.decodeFromString(ListSerializer(Long.serializer()), it.cardIdsJson) }.getOrNull() }
        if (!ids.isNullOrEmpty()) return cards.details(ids)
        val weak = active.weakWords(3).map { it.card }
        val rest = candidates(5).filter { c -> weak.none { it.card.id == c.card.id } }
        val chosen = (weak + rest).distinctBy { it.card.id }.take(5)
        if (chosen.isEmpty()) return emptyList()
        db.gamificationDao().upsertChallenge(DailyChallengeEntity(date, LexiJson.encodeToString(ListSerializer(Long.serializer()), chosen.map { it.card.id })))
        return chosen
    }

    suspend fun completeDailyChallenge(score: Int) {
        val date = clock.localDate(clock.now())
        val c = db.gamificationDao().challenge(date) ?: return
        if (c.completedAt != null) return
        db.gamificationDao().upsertChallenge(c.copy(completedAt = clock.now(), score = score))
        gamification.award(XpReason.DAILY_CHALLENGE)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeDailyChallenge() = com.lexiflow.util.ticks().map { clock.localDate(clock.now()) }.distinctUntilChanged()
        .flatMapLatest { db.gamificationDao().observeChallenge(it) }
}
