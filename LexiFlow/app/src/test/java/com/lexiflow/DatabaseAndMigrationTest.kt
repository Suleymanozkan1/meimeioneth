package com.lexiflow

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.repo.NewExample
import com.lexiflow.data.repo.NewSense
import com.lexiflow.data.repo.NewWord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseAndMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun onePerSenseCardAndCascadeDelete() = runBlocking {
        val c = testContainer()
        val deck = c.words.createDeck("Test", "USER")
        val wordId = c.words.insertWord(deck, NewWord("run", senses = listOf(
            NewSense("move fast", "koşmak", examples = listOf(NewExample("She runs every morning.", answer = "runs"))),
            NewSense("manage", "işletmek", examples = listOf(NewExample("He runs a small café.", answer = "runs"), NewExample("Bad example without word.", answer = "runs"))),
        )), "USER")
        val cards = c.db.cardDao().byWord(wordId)
        assertEquals(2, cards.size)
        assertEquals(2, c.db.wordDao().exampleCount(wordId)) // invalid example (answer not in sentence) rejected
        val summary = c.words.observeDecks().first().first { it.deck.id == deck }
        assertEquals(1, summary.wordCount); assertEquals(2, summary.newCount)
        c.words.deleteWord(wordId)
        assertTrue(c.db.cardDao().byWord(wordId).isEmpty())
        val restored = c.words.undoDelete()!!
        assertEquals(2, c.db.cardDao().byWord(restored).size)
        assertEquals(2, c.db.wordDao().exampleCount(restored))
    }

    @Test fun duplicateDeckNamesReuseDeck() = runBlocking {
        val c = testContainer()
        assertEquals(c.words.createDeck("A", "USER"), c.words.createDeck("A", "USER"))
    }

    @Test fun migrate1To2KeepsDataAndAddsTables() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO decks (id, name, description, source, createdAt, archived) VALUES (1, 'Mine', '', 'USER', 0, 0)")
            execSQL("INSERT INTO words (id, deckId, lemma, lemmaKey, richJson, tags, source, createdAt, updatedAt) VALUES (1, 1, 'postpone', 'postpone', '{}', '', 'USER', 0, 0)")
            execSQL("INSERT INTO senses (id, wordId, label, meaningTr, position) VALUES (1, 1, 'delay', 'ertelemek', 0)")
            execSQL("INSERT INTO cards (id, senseId, wordId, deckId, state, stability, difficulty, dueAt, reps, lapses, scheduledDays, suspended, createdAt) VALUES (1, 1, 1, 1, 2, 5.0, 4.0, 0, 3, 0, 5, 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT stability FROM cards WHERE id = 1").use { cur -> cur.moveToFirst(); assertEquals(5.0, cur.getDouble(0), 1e-9) }
        db.execSQL("INSERT INTO content_sources (kind, title, licenseStatus, licenseNote, origin, createdAt) VALUES ('MUSIC','x','PUBLIC_DOMAIN','n','BUILTIN',0)")
        db.execSQL("INSERT INTO lockscreen_questions (cardId, type, prompt, optionsJson, createdAt) VALUES (1, 'CLOZE', 'p', '[]', 0)")
        db.query("SELECT channel FROM lockscreen_questions").use { cur -> cur.moveToFirst(); assertEquals("NOTIFICATION", cur.getString(0)) }
        db.close()
    }

    @Test fun migrate2To3AddsAutopilotPriorityTable() {
        helper.createDatabase(DB, 2).apply {
            execSQL("INSERT INTO decks (id, name, description, source, createdAt, archived) VALUES (1, 'Mine', '', 'USER', 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 3, true, AppDatabase.MIGRATION_2_3)
        db.query("SELECT name FROM decks WHERE id = 1").use { cur -> cur.moveToFirst(); assertEquals("Mine", cur.getString(0)) }
        db.execSQL("INSERT INTO priority_items (kind, cardId, reason, localDate, createdAt) VALUES ('CARD', 1, 'AUTOPILOT', '2026-10-21', 0)")
        db.query("SELECT COUNT(*) FROM priority_items WHERE servedAt IS NULL").use { cur -> cur.moveToFirst(); assertEquals(1, cur.getInt(0)) }
        db.close()
    }

    @Test fun suspendedCardsAreNotDue() = runBlocking {
        val c = testContainer()
        val deck = c.words.createDeck("T", "USER")
        val w = c.words.insertWord(deck, NewWord("rely", senses = listOf(NewSense("depend", "güvenmek"))), "USER")
        val card = c.db.cardDao().byWord(w).first()
        c.study.setSuspended(card.id, true)
        assertTrue(c.db.cardDao().newCards(10).none { it.card.id == card.id })
        assertNull(c.study.retrievability(card))
    }

    companion object { const val DB = "migration-test" }
}
