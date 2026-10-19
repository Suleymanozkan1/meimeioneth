package com.lexiflow

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lexiflow.core.active.Skill
import com.lexiflow.core.fsrs.CardState
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.core.importer.TargetField
import com.lexiflow.data.importer.UnsupportedApkgException
import com.lexiflow.data.repo.ImportFormat
import com.lexiflow.data.repo.ImportRepository
import com.lexiflow.data.repo.SelfRating
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LockScreenAndImportTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun lockScreenGateRespectsQuietHoursCapAndInterval() = runBlocking {
        val clock = FakeClock()
        val c = testContainer(clock)
        c.contentLoader.ensureSeeded()
        var s = c.settings.current().copy(lockScreenEnabled = true, quietStartHour = 0, quietEndHour = 0, lockQuestionsPerDay = 2, lockIntervalMinutes = 30)
        c.settings.update { s }
        assertTrue(c.lockScreen.shouldPostNow(c.settings.current()))
        assertNotNull(c.lockScreen.createQuestion())
        assertFalse("interval", c.lockScreen.shouldPostNow(c.settings.current()))
        clock.advanceMinutes(31)
        assertNotNull(c.lockScreen.createQuestion())
        clock.advanceMinutes(31)
        assertFalse("daily cap", c.lockScreen.shouldPostNow(c.settings.current()))
        s = c.settings.current().copy(lockQuestionsPerDay = 10, quietStartHour = clock.hourOfDay(), quietEndHour = (clock.hourOfDay() + 1) % 24)
        c.settings.update { s }
        assertFalse("quiet", c.lockScreen.shouldPostNow(c.settings.current()))
        c.settings.update { it.copy(lockScreenEnabled = false) }
        assertFalse(c.lockScreen.shouldPostNow(c.settings.current()))
    }

    @Test fun lockScreenAnswersShareHistoriesAndOnlyReviewDueCards() = runBlocking {
        val clock = FakeClock()
        val c = testContainer(clock)
        c.contentLoader.ensureSeeded()
        // Make every card studied and due.
        c.study.reviewQueue().forEach { c.study.answer(it.card.id, Rating.EASY) }
        clock.advanceDays(40)
        val q = c.lockScreen.createQuestion()!!
        val before = c.db.reviewLogDao().forCard(q.card.card.id).size
        if (q.options.isEmpty()) {
            val r = c.lockScreen.answerProduction(q.entity.id, "I had to ${q.card.lemma} it because I was busy.")!!
            assertNotNull(r.bodyTr)
        } else {
            val r = c.lockScreen.answerChoice(q.entity.id, q.entity.correctIndex!!)!!
            assertTrue(r.correct); assertTrue(r.canSelfRate)
            c.lockScreen.selfRate(q.entity.id, SelfRating.CANT_USE)
            assertEquals(before + 1, c.db.reviewLogDao().forCard(q.card.card.id).size)
            assertEquals("NOTIFICATION", c.db.reviewLogDao().forCard(q.card.card.id).last().source)
            assertTrue(c.active.state(q.card.card.id, Skill.PRODUCTION).flaggedCantUse)
        }
        assertTrue(c.db.practiceDao().forCard(q.card.card.id).any { it.source == "LOCKSCREEN" })
        // A card that is not due never gets an FSRS review from the lock screen.
        val notDue = c.db.cardDao().allStudied().first { it.card.id != q.card.card.id }
        c.study.answer(notDue.card.id, Rating.GOOD)
        val logs = c.db.reviewLogDao().forCard(notDue.card.id).size
        assertFalse(c.study.isDue(notDue.card.id))
        val id = c.db.lockScreenDao().insert(com.lexiflow.data.db.LockScreenQuestionEntity(cardId = notDue.card.id, type = "EN_TO_TR", prompt = "x", optionsJson = "[\"a\",\"b\",\"c\"]", correctIndex = 0, createdAt = clock.now()))
        c.lockScreen.answerChoice(id, 1) // wrong
        assertEquals(logs, c.db.reviewLogDao().forCard(notDue.card.id).size)
    }

    @Test fun lockQuestionHintDoesNotRevealAnswerAndDontKnowCountsAsAgain() = runBlocking {
        val clock = FakeClock()
        val c = testContainer(clock)
        c.contentLoader.ensureSeeded()
        c.study.reviewQueue().forEach { c.study.answer(it.card.id, Rating.EASY) }
        clock.advanceDays(40)
        repeat(4) { round ->
            val q = c.lockScreen.createQuestion()!!
            val type = com.lexiflow.core.exercise.QuickQuestionType.valueOf(q.entity.type)
            val hint = c.lockScreen.hint(q)
            assertTrue(hint.isNotBlank())
            if (type == com.lexiflow.core.exercise.QuickQuestionType.CLOZE || type == com.lexiflow.core.exercise.QuickQuestionType.TR_TO_EN) {
                assertFalse("hint gives away '${q.card.lemma}': $hint", Regex("(?i)\\b${Regex.escape(q.card.lemma)}\\b").containsMatchIn(hint))
            }
            val card = q.card.card.id
            val due = c.study.isDue(card)
            val before = c.db.reviewLogDao().forCard(card).size
            val r = c.lockScreen.answerDontKnow(q.entity.id)!!
            assertFalse(r.correct)
            assertTrue(r.titleTr.contains("Doğru cevap"))
            assertEquals("DONT_KNOW", c.db.lockScreenDao().get(q.entity.id)!!.outcome)
            if (due) assertEquals(Rating.AGAIN.value, c.db.reviewLogDao().forCard(card).last().rating)
            else assertEquals(before, c.db.reviewLogDao().forCard(card).size)
            // Answering again (second tap, notification) changes nothing.
            assertTrue(c.lockScreen.answerDontKnow(q.entity.id)!!.titleTr.contains("zaten"))
            clock.advanceMinutes(31L + round)
        }
        val drill = com.lexiflow.core.language.GrammarDrills.all.first { it.ruleId == "mistake_do" }
        val ex = com.lexiflow.domain.ExerciseBuilder.grammarDrill(drill)
        assertTrue(ex.explanationTr!!.contains("make a mistake"))
    }

    @Test fun csvImportReportsDuplicatesSensesAndErrors() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        val f = File(ctx.cacheDir, "deck.csv")
        f.writeText("word,meaning,example,tags\npostpone,ertelemek,We postponed it.,b1\npostpone,ileri bir tarihe atmak; tehir etmek,,\nserendipity,mutlu tesadüf,It was pure serendipity.,c1\n,boş satır,,\nkettle,su ısıtıcısı,Put the water on.,home\n")
        val p = c.importer.load(Uri.fromFile(f))
        assertEquals(ImportFormat.CSV_TSV, p.format)
        assertEquals(listOf(TargetField.WORD, TargetField.MEANING_TR, TargetField.EXAMPLE, TargetField.TAGS), p.mapping)
        val report = c.importer.import(p, p.mapping, "Benim destem")
        assertEquals(1, report.duplicatesSkipped.size)
        assertEquals(1, report.newSenses)
        assertEquals(2, report.imported)
        assertEquals(1, report.errors.size)
        assertTrue(report.notesTr.any { it.contains("kettle") })
        val seren = c.words.wordsByLemma("serendipity").single()
        assertEquals(1, c.db.wordDao().exampleCount(seren.id))
        assertEquals(2, c.db.cardDao().byWord(c.words.wordsByLemma("postpone").single().id).size)
    }

    @Test fun encodingDetection() {
        val (t1, e1) = ImportRepository.decode("çay,tea".toByteArray(Charsets.UTF_8))
        assertEquals("UTF-8", e1); assertEquals("çay,tea", t1)
        val (t2, e2) = ImportRepository.decode("ğüşıöç".toByteArray(charset("windows-1254")))
        assertTrue(e2.startsWith("Windows-1254")); assertEquals("ğüşıöç", t2)
    }

    @Test fun apkgImportReplaysRealHistory() = runBlocking {
        val c = testContainer()
        val apkg = buildLegacyApkg()
        val p = c.importer.load(Uri.fromFile(apkg))
        assertEquals(ImportFormat.APKG, p.format)
        assertEquals("Anki::Vocab", p.suggestedDeckName)
        assertEquals(listOf("Front", "Back", "Tags"), p.table.header)
        val r = c.importer.import(p, p.mapping, p.suggestedDeckName)
        assertEquals(2, r.imported)
        assertEquals(1, r.historyCards)
        assertEquals(3, r.historyReviews)
        val word = c.words.wordsByLemma("diligent").single()
        val card = c.db.cardDao().byWord(word.id).single()
        assertEquals(CardState.REVIEW.value, card.state)
        assertTrue(card.stability > 0)
        assertEquals(3, c.db.reviewLogDao().forCard(card.id).count { it.source == "IMPORT_ANKI" })
        val other = c.db.cardDao().byWord(c.words.wordsByLemma("sincere").single().id).single()
        assertEquals(CardState.NEW.value, other.state) // no invented history
    }

    @Test fun newFormatApkgIsRejectedWithGuidance() {
        val f = File(ctx.cacheDir, "new.apkg")
        ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("collection.anki21b")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry() }
        val c = testContainer()
        val e = runCatching { runBlocking { c.importer.load(Uri.fromFile(f)) } }.exceptionOrNull()
        assertTrue(e is UnsupportedApkgException)
        assertTrue(e!!.message!!.contains("Support older Anki versions"))
    }

    @Test fun apkgCodeFieldIsSkippedAndDeckCanBeDeleted() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        // Like "Close-up Word List::C2": a code field first, then the word, the Turkish meaning and an example.
        val apkg = buildLegacyApkg(
            fields = listOf("Text", "Field 2", "Field 3", "Field 4"),
            notes = listOf(
                "C2.06.026\u001fcast lots\u001fkura çekmek\u001fThey cast lots to choose a leader.",
                "C2.06.027\u001fstand a chance\u001fşansı olmak\u001fWe still stand a chance.",
            ),
            deck = "Close-up Word List\u001fC2",
        )
        val p = c.importer.load(Uri.fromFile(apkg))
        assertEquals(listOf(TargetField.IGNORE, TargetField.WORD, TargetField.MEANING_TR, TargetField.EXAMPLE, TargetField.TAGS), p.mapping)
        val r = c.importer.import(p, p.mapping, p.suggestedDeckName)
        assertEquals(2, r.imported)
        val word = c.words.wordsByLemma("cast lots").single()
        assertEquals("kura çekmek", c.db.wordDao().senses(word.id).single().meaningTr)
        assertTrue(c.words.wordsByLemma("C2.06.026").isEmpty())

        // The imported deck can be deleted with all its words, cards and history; other decks stay.
        val deckId = word.deckId
        val card = c.db.cardDao().byWord(word.id).single()
        c.study.answer(card.id, Rating.GOOD)
        val q = c.db.lockScreenDao().insert(com.lexiflow.data.db.LockScreenQuestionEntity(cardId = card.id, type = "EN_TO_TR", prompt = "x", optionsJson = "[]", correctIndex = 0, createdAt = 0))
        val otherDecks = c.db.deckDao().count() - 1
        c.words.deleteDeck(deckId)
        assertEquals(null, c.db.deckDao().get(deckId))
        assertTrue(c.words.wordsByLemma("cast lots").isEmpty())
        assertTrue(c.db.cardDao().byWord(word.id).isEmpty())
        assertTrue(c.db.reviewLogDao().forCard(card.id).isEmpty())
        assertEquals(null, c.db.lockScreenDao().get(q))
        assertEquals(otherDecks, c.db.deckDao().count())
        assertTrue(c.study.reviewQueue().isNotEmpty()) // built-in deck untouched
    }

    @Test fun bareAnkiDeckIsEnrichedAndSplitByLevel() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        // Like a typical "English / Turkish" deck: no examples, no definitions.
        val apkg = buildLegacyApkg(
            fields = listOf("English", "Turkish"),
            notes = listOf("borrow\u001födünç almak", "hypothesis\u001fvarsayım; hipotez", "curious\u001fmeraklı", "curious\u001ftuhaf, garip", "flibbertigibbet\u001fgevezenin teki"),
            deck = "İngilizce - Türkçe",
        )
        val p = c.importer.load(Uri.fromFile(apkg))
        val r = c.importer.import(p, p.mapping, p.suggestedDeckName, splitByLevel = true)
        assertEquals(4, r.imported) // the second "curious" is a new sense of the first
        assertTrue(r.notesTr.any { it.contains("LexiFlow'un kendi") })
        val borrow = c.words.wordsByLemma("borrow").single()
        assertEquals("İngilizce - Türkçe · A2", c.db.deckDao().get(borrow.deckId)!!.name)
        assertEquals("A2", borrow.cefr)
        val sense = c.db.wordDao().senses(borrow.id).single()
        assertEquals("ödünç almak", sense.meaningTr) // the learner's meaning is kept
        assertTrue(!sense.definitionEn.isNullOrBlank())
        assertTrue(c.db.wordDao().examplesForWord(borrow.id).size >= 3)
        assertEquals("İngilizce - Türkçe · C1", c.db.deckDao().get(c.words.wordsByLemma("hypothesis").single().deckId)!!.name)
        // Each "curious" sense gets the examples of its own meaning.
        val curious = c.words.wordsByLemma("curious").single()
        val curiousSenses = c.db.wordDao().senses(curious.id)
        assertEquals(2, curiousSenses.size)
        val exBySense = c.db.wordDao().examplesForWord(curious.id).groupBy { it.senseId }
        assertTrue(curiousSenses.all { (exBySense[it.id]?.size ?: 0) >= 3 })
        // Unknown words stay in the main deck without a level.
        val unknown = c.words.wordsByLemma("flibbertigibbet").single()
        assertEquals("İngilizce - Türkçe", c.db.deckDao().get(unknown.deckId)!!.name)
        assertEquals(null, unknown.cefr)
    }

    @Test fun existingDeckCanBeEnrichedAndSplitLater() = runBlocking {
        val c = testContainer()
        val deck = c.words.createDeck("Eski deste", "IMPORT_APKG")
        c.words.insertWord(deck, com.lexiflow.data.repo.NewWord("borrow", senses = listOf(com.lexiflow.data.repo.NewSense("x", "ödünç almak"))), source = "IMPORT")
        c.words.insertWord(deck, com.lexiflow.data.repo.NewWord("hypothesis", senses = listOf(com.lexiflow.data.repo.NewSense("x", "varsayım"))), source = "IMPORT")
        val card = c.db.cardDao().byWord(c.words.wordsByLemma("borrow").single().id).single()
        c.study.answer(card.id, Rating.GOOD)
        val r = c.deckTools.enrichDeck(deck)
        assertEquals(2, r.words)
        assertTrue(r.examples >= 6)
        assertEquals(2, r.levels)
        // Running it again adds nothing.
        assertEquals(0, c.deckTools.enrichDeck(deck).examples)
        val moved = c.deckTools.splitByLevel(deck)
        assertEquals(mapOf("A2" to 1, "C1" to 1), moved)
        assertEquals(null, c.db.deckDao().get(deck)) // emptied deck is removed
        val borrow = c.words.wordsByLemma("borrow").single()
        val movedCard = c.db.cardDao().byWord(borrow.id).single()
        assertEquals(borrow.deckId, movedCard.deckId)
        assertEquals(1, c.db.reviewLogDao().forCard(movedCard.id).size) // history moves with the card
    }

    private fun buildLegacyApkg(
        fields: List<String> = listOf("Front", "Back"),
        notes: List<String> = listOf("diligent\u001fçalışkan<br>gayretli", "sincere\u001f[sound:x.mp3]samimi"),
        deck: String = "Anki\u001fVocab",
    ): File {
        val dbFile = File(ctx.cacheDir, "collection.anki2").also { it.delete() }
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE col (id integer primary key, models text, decks text)")
            db.execSQL("CREATE TABLE notes (id integer primary key, mid integer, flds text, tags text)")
            db.execSQL("CREATE TABLE cards (id integer primary key, nid integer, did integer, ord integer, queue integer)")
            db.execSQL("CREATE TABLE revlog (id integer primary key, cid integer, ease integer, ivl integer, type integer)")
            val flds = fields.withIndex().joinToString(",") { (i, n) -> "{\"name\":\"$n\",\"ord\":$i}" }
            db.execSQL("INSERT INTO col VALUES (1, ?, ?)", arrayOf("{\"1001\":{\"name\":\"Basic\",\"flds\":[$flds]}}", "{\"7\":{\"name\":\"$deck\"}}"))
            notes.forEachIndexed { i, n -> db.execSQL("INSERT INTO notes VALUES (?, 1001, ?, ?)", arrayOf(i + 1, n, if (i == 0) "b2 adj" else "")) }
            db.execSQL("INSERT INTO cards VALUES (11, 1, 7, 0, 2)")
            db.execSQL("INSERT INTO cards VALUES (12, 2, 7, 0, 0)")
            val t0 = 1_700_000_000_000L
            db.execSQL("INSERT INTO revlog VALUES ($t0, 11, 3, 0, 0)")
            db.execSQL("INSERT INTO revlog VALUES (${t0 + 600_000}, 11, 3, 1, 0)")
            db.execSQL("INSERT INTO revlog VALUES (${t0 + 3 * 86_400_000L}, 11, 4, 8, 1)")
            db.execSQL("INSERT INTO revlog VALUES (${t0 + 4 * 86_400_000L}, 11, 0, 8, 4)")
        }
        val apkg = File(ctx.cacheDir, "test.apkg")
        ZipOutputStream(apkg.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("collection.anki2")); dbFile.inputStream().use { it.copyTo(z) }; z.closeEntry()
            z.putNextEntry(ZipEntry("media")); z.write("{\"0\":\"x.mp3\"}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("0")); z.write(byteArrayOf(0)); z.closeEntry()
        }
        return apkg
    }
}
