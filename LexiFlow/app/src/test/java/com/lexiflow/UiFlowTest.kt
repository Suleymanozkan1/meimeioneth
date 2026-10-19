package com.lexiflow

import android.Manifest
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.lexiflow.data.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Compose UI tests run on the JVM with Robolectric (no emulator available in CI). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<LexiFlowApp>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Robolectric runs the main looper in PAUSED mode: coroutine results posted to the main thread are only
     * delivered when the looper is idled, so wait helpers idle it explicitly.
     */
    private fun waitCond(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(30)
        }
        val tree = runCatching { rule.onRoot(useUnmergedTree = true).printToString(maxDepth = 12) }.getOrDefault("")
        throw AssertionError("condition not met in $timeoutMs ms. Screen:\n" + tree.lines().filter { "Text =" in it || "Tag:" in it }.joinToString("\n"))
    }

    private fun waitFor(matcher: SemanticsMatcher) = waitCond { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun prepare(onboardingDone: Boolean) = runBlocking {
        app.container.settings.update { AppSettings(onboardingDone = onboardingDone, builtinContentVersion = it.builtinContentVersion) }
        app.container.contentLoader.ensureSeeded()
    }

    @Test fun onboardingLeadsToHome() {
        prepare(onboardingDone = false)
        waitFor(hasTestTag("onboarding"))
        rule.onNodeWithTag("onboarding_start").performScrollTo().performClick()
        waitFor(hasTestTag("home"))
        assertTrue(runBlocking { app.container.settings.current().onboardingDone })
    }

    @Test fun reviewCardAndSaveAnswer() {
        prepare(onboardingDone = true)
        waitFor(hasTestTag("start_review"))
        val before = runBlocking { app.container.db.reviewLogDao().total() }
        rule.onNodeWithTag("start_review").performClick()
        waitFor(hasTestTag("reveal"))
        // Recall side first: meaning and a gapped sentence, never the word itself.
        rule.onNodeWithTag("review_front_meaning").assertExists()
        rule.onNodeWithTag("review_word").assertDoesNotExist()
        rule.onNodeWithTag("reveal").performScrollTo().performClick()
        waitFor(hasTestTag("rate_good"))
        rule.onNodeWithTag("review_word").assertExists()
        rule.onNodeWithTag("cant_use").performScrollTo().performClick()
        rule.onNodeWithTag("rate_good").performScrollTo().performClick()
        waitCond { runBlocking { app.container.db.reviewLogDao().total() } == before + 1 }
        val flagged = runBlocking { app.container.db.skillDao().allForSkill("PRODUCTION").count { it.flaggedCantUse } }
        assertTrue(flagged >= 1)
    }

    @Test fun changeDailyGoalInSettings() {
        prepare(onboardingDone = true)
        waitFor(hasTestTag("tab_settings"))
        rule.onNodeWithTag("tab_settings").performClick()
        waitFor(hasTestTag("goal_slider"))
        rule.onNodeWithTag("goal_slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        waitCond { runBlocking { app.container.settings.current().dailyGoalXp } == 100 }
    }

    @Test fun openImportFromSettings() {
        prepare(onboardingDone = true)
        waitFor(hasTestTag("tab_settings"))
        rule.onNodeWithTag("tab_settings").performClick()
        rule.onNodeWithText("Anki / CSV içe aktar").performScrollTo().performClick()
        waitFor(hasTestTag("import_pick"))
    }

    @Test fun drillAnswerIsGraded() {
        prepare(onboardingDone = true)
        waitFor(hasTestTag("home"))
        rule.onNodeWithTag("home").performScrollToNode(hasTestTag("mode_Türkçeden İngilizceye"))
        rule.onNodeWithTag("mode_Türkçeden İngilizceye").performClick()
        waitFor(hasTestTag("drill_input"))
        rule.onNodeWithTag("drill_input").performTextInput("wrongword")
        rule.onNodeWithTag("drill_submit").performScrollTo().performClick()
        waitFor(hasTestTag("drill_feedback"))
        waitFor(hasText("Doğru cevap", substring = true))
        val attempts = runBlocking { app.container.db.skillDao().allForSkill("RECALL").sumOf { it.attempts } }
        assertEquals(1, attempts)
    }

    @Test fun lockScreenSettingsAndInAppQuestion() {
        prepare(onboardingDone = true)
        waitFor(hasTestTag("home"))
        rule.onNodeWithTag("home").performScrollToNode(hasTestTag("mode_Kilit Ekranı Soruları"))
        rule.onNodeWithTag("mode_Kilit Ekranı Soruları").performClick()
        waitFor(hasTestTag("lock_switch"))
        rule.onNodeWithTag("lock_switch").performScrollTo().performClick()
        waitCond { runBlocking { app.container.settings.current().lockScreenEnabled } }
        rule.onNodeWithTag("lock_test").performScrollTo().performClick()
        rule.onNodeWithText("Bir soruyu uygulamada çöz").performScrollTo().performClick()
        waitFor(hasTestTag("lockq_prompt"))
    }
}
