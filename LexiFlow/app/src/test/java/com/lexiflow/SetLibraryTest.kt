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
        val all = c.setLibrary.sets()
        val sets = all.filter { !it.isLevelPack }
        assertTrue("expected the 12 shipped sets", sets.size >= 12)
        // Every set is grouped under one CEFR level, and the list runs A1 → C2.
        val order = com.lexiflow.data.content.SetLibraryRepository.LEVELS
        assertTrue(all.all { it.group in order })
        assertEquals(all.map { order.indexOf(it.group) }.sorted(), all.map { order.indexOf(it.group) })
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

    @Test fun dictionaryEntriesAreUsable() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val files = ctx.assets.list("lexicon").orEmpty().filter { it.endsWith(".json") }
        assertTrue("dictionary files shipped", files.isNotEmpty())
        val index = com.lexiflow.data.content.LocalContentIndex(ctx)
        var total = 0
        files.forEach { f ->
            val words = com.lexiflow.core.ai.LexiJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(com.lexiflow.data.content.SeedWord.serializer()),
                ctx.assets.open("lexicon/$f").bufferedReader().use { it.readText() },
            )
            words.forEach { w ->
                total++
                assertTrue("${w.lemma} level ${w.cefr}", w.cefr in com.lexiflow.data.repo.ImportRepository.CEFR_LEVELS)
                assertNotNull("${w.lemma} indexed", index.find(w.lemma))
                val sense = w.senses.first()
                val stored = sense.examples.filter { it.en.contains(it.answer, ignoreCase = true) }
                assertTrue("${w.lemma}: examples", stored.size >= 3)
                assertTrue("${w.lemma}: cloze", stored.count { ClozeBuilder.build(it.en, it.answer) != null } >= 2)
            }
        }
        assertTrue("dictionary size $total", total >= 2000)
    }

    @Test fun levelPacksHoldEveryWordOfTheirLevel() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        val packs = c.setLibrary.sets().filter { it.isLevelPack }
        assertEquals(listOf("A1", "A2", "B1", "B2", "C1", "C2"), packs.map { it.group })
        packs.forEach { p ->
            val words = c.setLibrary.words(p.id)
            assertEquals(p.wordCount, words.size)
            assertTrue(words.all { it.cefr == p.group })
        }
        assertTrue(packs.sumOf { it.wordCount } >= 2500)
        // A pack installs like a set; words already present from another set are skipped.
        val a2 = c.setLibrary.install("a2-everyday")
        val pack = packs.first { it.group == "A2" }
        val r = c.setLibrary.install(pack.id)
        assertEquals(pack.wordCount, r.added + r.skippedDuplicates.size)
        assertTrue(r.skippedDuplicates.isNotEmpty() || a2.added == 0)
        assertEquals("A2 Seviye Paketi", c.db.deckDao().get(r.deckId)!!.name)
    }
}
