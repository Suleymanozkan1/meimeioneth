package com.lexiflow.core

import com.lexiflow.core.active.ActivePracticeScheduler
import com.lexiflow.core.active.HintBuilder
import com.lexiflow.core.active.PracticeResult
import com.lexiflow.core.active.Skill
import com.lexiflow.core.active.SkillState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivePracticeTest {
    private val s = ActivePracticeScheduler()
    private val now = 1_700_000_000_000L

    @Test fun successFadesHintsAndGrowsInterval() {
        var st = SkillState(Skill.PRODUCTION)
        st = s.update(st, PracticeResult(1.0, hintUsed = 0, at = now))
        assertEquals(2, st.hintLevel)
        assertEquals(1, st.consecutiveSuccesses)
        val first = st.dueAt - now
        st = s.update(st, PracticeResult(1.0, 0, at = st.dueAt))
        assertTrue(st.dueAt - st.lastPracticedAt!! > first)
    }

    @Test fun hintedAnswerCountsLess() {
        val base = SkillState(Skill.RECALL, mastery = 0.5)
        val unaided = s.update(base, PracticeResult(1.0, 0, at = now))
        val hinted = s.update(base, PracticeResult(1.0, 3, at = now))
        assertTrue(unaided.mastery > hinted.mastery)
        assertTrue(hinted.dueAt < unaided.dueAt)
    }

    @Test fun failureComesBackSoonWithMoreHints() {
        val st = s.update(SkillState(Skill.PRODUCTION, hintLevel = 1), PracticeResult(0.1, 0, at = now))
        assertEquals(2, st.hintLevel)
        assertEquals(now + 20 * 60_000L, st.dueAt)
    }

    @Test fun cantUseFlagCapsMasteryAndRaisesPriority() {
        val strong = SkillState(Skill.PRODUCTION, mastery = 0.9, dueAt = now + 10 * 86_400_000L, hintLevel = 0)
        val flagged = s.flagCantUse(strong, now)
        assertEquals(0.3, flagged.mastery, 1e-9)
        assertTrue(flagged.flaggedCantUse)
        assertEquals(now, flagged.dueAt)
        assertTrue(s.priority(flagged, 0.95, now) > s.priority(strong, 0.95, now))
        val cleared = s.update(flagged, PracticeResult(1.0, 0, at = now))
        assertFalse(cleared.flaggedCantUse)
    }

    @Test fun hintLadder() {
        assertEquals(0, HintBuilder.hint(0, "postpone", "ertelemek", "We ___ it.").size)
        assertEquals(listOf("İlk harf: p… (8 harf)"), HintBuilder.hint(1, "postpone", "ertelemek", null))
        assertEquals(3, HintBuilder.hint(3, "postpone", "ertelemek", "We ___ it.").size)
    }
}
