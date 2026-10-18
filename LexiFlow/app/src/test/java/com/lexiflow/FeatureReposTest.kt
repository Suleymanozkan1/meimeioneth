package com.lexiflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lexiflow.core.ai.QuestionType
import com.lexiflow.core.competition.BattleMode
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.core.pronunciation.AsrHypothesis
import com.lexiflow.core.pronunciation.AudioSignals
import com.lexiflow.data.remote.RemoteError
import com.lexiflow.data.repo.CultureRepository
import com.lexiflow.data.repo.StoryOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureReposTest {

    private suspend fun studied(clock: FakeClock = FakeClock()) = testContainer(clock).also { c ->
        c.contentLoader.ensureSeeded()
        c.study.reviewQueue().forEach { c.study.answer(it.card.id, Rating.GOOD); clock.advanceMinutes(11); c.study.answer(it.card.id, Rating.HARD) }
        clock.advanceDays(10)
    }

    @Test fun offlineForgettingCurveStoryDoesNotTouchFsrs() = runBlocking {
        val clock = FakeClock()
        val c = studied(clock)
        val logsBefore = c.db.reviewLogDao().total()
        val g = c.stories.generate(StoryOptions(theme = "Work", targetCount = 4, forgettingCurve = true))
        assertTrue(g.noticeTr!!.contains("AI"))
        val story = c.stories.load(g.id)!!
        assertEquals("OFFLINE_TEMPLATE", story.entity.origin)
        assertEquals(4, story.words.size)
        assertTrue(story.words.all { it.occurrences >= 1 })
        val q = story.story.questions.first { it.type == QuestionType.MEANING_IN_CONTEXT }
        c.stories.recordQuestion(story, q, 1.0, null)
        c.stories.finish(g.id, 5, 10)
        assertEquals(logsBefore, c.db.reviewLogDao().total())
        assertTrue(c.db.storyDao().get(g.id)!!.completedAt != null)
    }

    @Test fun storyFallsBackWhenAiServerFails() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(503).setBody("{\"code\":\"ai_unavailable\"}")); start() }
        val c = studied()
        c.settings.update { it.copy(backendUrl = server.url("/").toString(), aiEnabled = true, serverToken = "t") }
        val g = c.stories.generate(StoryOptions(targetCount = 3))
        assertTrue(g.noticeTr!!.contains("AI sağlayıcısı"))
        assertEquals("OFFLINE_TEMPLATE", c.stories.load(g.id)!!.entity.origin)
        server.shutdown()
    }

    @Test fun invalidAiJsonIsRejected() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("{\"title\":\"x\",\"paragraphs\":[\"too short\"]}")); start() }
        val c = testContainer()
        c.settings.update { it.copy(backendUrl = server.url("/").toString(), aiEnabled = true, serverToken = "t") }
        val e = runCatching { c.backend.story(com.lexiflow.core.ai.StoryRequest(listOf(com.lexiflow.core.ai.StoryTarget("postpone")), wordCount = 150)) }.exceptionOrNull()
        assertTrue(e is RemoteError.InvalidOutput)
        server.shutdown()
    }

    @Test fun offlineSpeakingCoachEvaluatesTypedAnswer() = runBlocking {
        val c = studied()
        val (id, opener) = c.speaking.start("Decisions")
        assertTrue(opener.isNotBlank())
        val reply = c.speaking.answer(id, "I made a party yesterday and it was fun.", voice = false, speakingMs = 0, topic = "Decisions")
        assertTrue(reply.feedback.evaluation.issues.any { it.ruleId == "party_make" })
        assertEquals("OFFLINE_COACH", reply.feedback.engine)
        assertEquals(3, c.speaking.observeTurns(id).first().size)
    }

    @Test fun pronunciationAttemptStoresTranscriptOnly() = runBlocking {
        val c = studied()
        val card = c.db.cardDao().allStudied().first()
        val r = c.pronunciation.score(card.lemma, listOf(AsrHypothesis(card.lemma, 0.9f)), AudioSignals(900, 6f), card.card.id, "PRON_WORD")
        assertEquals(100, r.intelligibility)
        assertEquals(1, c.db.pronunciationDao().count())
        c.maintenance.deletePronunciation()
        assertEquals(0, c.db.pronunciationDao().count())
    }

    @Test fun cultureClozeLinksToDeckCardsAndTranscriptImport() = runBlocking {
        val c = studied()
        val items = c.culture.items(listOf("MUSIC", "FILM", "TV", "ORIGINAL_SCENE"))
        assertTrue(items.size >= 20)
        assertTrue(items.filter { it.first.isQuote }.all { it.second.licenseStatus == "PUBLIC_DOMAIN" })
        val linked = items.first { c.culture.cardFor(it.first) != null }.first
        c.culture.recordCloze(linked, true)
        val n = c.culture.importTranscript("My subtitles", "USER_TRANSCRIPT", "1\n00:00:01,000 --> 00:00:03,000\nWe had to postpone the trip.\n\n2\n00:00:04,000 --> 00:00:05,000\nNothing here.\n")
        assertEquals(1, n)
        assertEquals("00:00:01", CultureRepository.parseSubtitle("1\n00:00:01,000 --> 00:00:03,000\nHello there.\n").first().first)
    }

    @Test fun botBattleIsScoredByTheSameRules() = runBlocking {
        val c = studied()
        val round = c.competition.newBotRound(BattleMode.FIVE_WORD_SENTENCE)
        assertEquals(5, round.words.size)
        val outcome = c.competition.finishBotRound(round, round.words.joinToString(" "))
        assertTrue(outcome.mine.issues.any { it.ruleId == "word_list" })
        assertFalse(outcome.iWon == true && outcome.bot!!.scores.overall > outcome.mine.scores.overall)
        assertEquals(1, c.db.competitionDao().count())
    }
}
