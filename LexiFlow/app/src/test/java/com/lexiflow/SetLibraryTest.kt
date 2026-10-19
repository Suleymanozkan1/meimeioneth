package com.lexiflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lexiflow.core.exercise.ClozeBuilder
import com.lexiflow.core.importer.DuplicateDetector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetLibraryTest {

    @Test fun everySetIsCompleteAndUsableForExercises() {
        val c = testContainer()
        val sets = c.setLibrary.sets()
        assertTrue("expected the 12 shipped sets", sets.size >= 12)
        val seen = mutableSetOf<String>()
        sets.forEach { info ->
            val words = c.setLibrary.words(info.id)
            assertEquals("${info.id} word count", info.wordCount, words.size)
            words.forEach { w ->
                assertTrue("${w.lemma} appears in two sets", seen.add(DuplicateDetector.normalizeWord(w.lemma)))
                val sense = w.senses.first()
                assertTrue("${w.lemma} meaning", sense.meaningTr.isNotBlank())
                assertTrue("${w.lemma} definition", !sense.definitionEn.isNullOrBlank())
                // The same filter the app applies when storing examples, and the cloze builder used by exercises.
                val stored = sense.examples.filter { it.en.contains(it.answer, ignoreCase = true) }
                assertTrue("${w.lemma}: ${sense.examples.size - stored.size} examples lost", stored.size == sense.examples.size && stored.size >= 4)
                val clozes = stored.count { ClozeBuilder.build(it.en, it.answer) != null }
                assertTrue("${w.lemma}: only $clozes cloze-able examples", clozes >= 3)
                assertTrue("${w.lemma}: Turkish translations", stored.all { !it.tr.isNullOrBlank() })
            }
        }
    }

    @Test fun addingASetCreatesADeckOnceAndCanBeRemoved() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        val id = "a2-everyday"
        assertNull(c.setLibrary.states().first { it.info.id == id }.deckId)
        val r = c.setLibrary.install(id)
        assertEquals(30, r.added + r.skippedDuplicates.size)
        assertTrue(r.added >= 25)
        val deck = c.db.deckDao().get(r.deckId)!!
        assertEquals("SET:$id", deck.source)
        assertEquals(r.added, c.words.observeWords(r.deckId).first().size)
        assertNotNull(c.setLibrary.states().first { it.info.id == id }.deckId)
        // Adding again never duplicates words or decks.
        val again = c.setLibrary.install(id)
        assertEquals(r.deckId, again.deckId)
        assertEquals(0, again.added)
        // The deck behaves like any other: it can be deleted and added again later.
        c.words.deleteDeck(r.deckId)
        assertNull(c.setLibrary.states().first { it.info.id == id }.deckId)
        assertEquals(r.added, c.setLibrary.install(id).added)
    }
}
