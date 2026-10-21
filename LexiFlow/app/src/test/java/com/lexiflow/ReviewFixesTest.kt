package com.lexiflow

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.data.db.ExampleEntity
import com.lexiflow.data.repo.NewSense
import com.lexiflow.data.repo.NewWord
import com.lexiflow.ui.screens.ReviewViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReviewFixesTest {

    @Test fun lockClozeFallsBackWhenAnswerIsNotAWholeWord() = runBlocking {
        val c = testContainer()
        val deck = c.words.createDeck("T", "USER")
        val w = c.words.insertWord(deck, NewWord("run", senses = listOf(NewSense("move", "koşmak"))), "USER")
        val sense = c.db.wordDao().senses(w).single()
        c.db.wordDao().insertExample(ExampleEntity(senseId = sense.id, wordId = w, en = "She was running late.", answer = "run", origin = "USER"))
        c.settings.update { it.copy(lockPool = com.lexiflow.data.settings.LockPool.MIXED) }
        repeat(8) { assertNotNull(c.lockScreen.createQuestion()) } // all rotation types, none may crash
    }

    @Test fun lockAnswerIsRecordedOnlyOnce() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        var q = c.lockScreen.createQuestion()!!
        while (q.options.isEmpty()) q = c.lockScreen.createQuestion()!!
        c.lockScreen.answerChoice(q.entity.id, q.entity.correctIndex!!)
        val again = c.lockScreen.answerChoice(q.entity.id, q.entity.correctIndex!!)!!
        assertTrue(again.titleTr.contains("zaten"))
        assertEquals(1, c.db.practiceDao().forCard(q.card!!.card.id).count { it.source == "LOCKSCREEN" })
    }

    @Test fun definitionOnlyImportKeepsMeaningAndReimportIsDuplicate() = runBlocking {
        val c = testContainer()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.cacheDir, "defs.csv").apply { writeText("word,definition\nkettle,a pot for boiling water\n") }
        val p = c.importer.load(Uri.fromFile(f))
        c.importer.import(p, p.mapping, "Defs")
        val sense = c.db.wordDao().senses(c.words.wordsByLemma("kettle").single().id).single()
        assertEquals("a pot for boiling water", sense.meaningTr)
        val again = c.importer.import(c.importer.load(Uri.fromFile(f)), p.mapping, "Defs")
        assertEquals(1, again.duplicatesSkipped.size)
        assertEquals(0, again.newSenses)
    }

    @Test fun learningStepsComeBackInTheSameSessionAndDoubleTapIsIgnored() = runBlocking {
        val clock = FakeClock()
        val c = testContainer(clock)
        c.contentLoader.ensureSeeded()
        val vm = ReviewViewModel(c, free = false, deckId = null)
        val deadline = System.currentTimeMillis() + 10_000
        fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        while (vm.state.value.loading && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(20) }
        val first = vm.state.value.current!!.card.id
        val size = vm.state.value.queue.size
        vm.reveal(); while (!vm.state.value.revealed) { idle(); Thread.sleep(20) }
        vm.answer(Rating.GOOD); vm.answer(Rating.GOOD) // double tap
        while (vm.state.value.answering || vm.state.value.index == 0) { idle(); Thread.sleep(20) }
        assertEquals(1, vm.state.value.index)
        assertEquals(size + 1, vm.state.value.queue.size)
        assertEquals(first, vm.state.value.queue.last().card.id)
        assertEquals(1, c.db.reviewLogDao().forCard(first).size)
    }
}
