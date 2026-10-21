package com.lexiflow.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.lexiflow.ui.components.LoadingBox
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lexiflow.data.repo.StudyMode
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.screens.BattleScreen
import com.lexiflow.ui.screens.CultureItemScreen
import com.lexiflow.ui.screens.CultureScreen
import com.lexiflow.ui.screens.DailyWordsScreen
import com.lexiflow.ui.screens.DeckDetailScreen
import com.lexiflow.ui.screens.DecksScreen
import com.lexiflow.ui.screens.DrillScreen
import com.lexiflow.ui.screens.HomeScreen
import com.lexiflow.ui.screens.ImportScreen
import com.lexiflow.ui.screens.LockQuestionScreen
import com.lexiflow.ui.screens.LockScreenScreen
import com.lexiflow.ui.screens.ModesScreen
import com.lexiflow.ui.screens.OnboardingScreen
import com.lexiflow.ui.screens.ProgressScreen
import com.lexiflow.ui.screens.PronunciationScreen
import com.lexiflow.ui.screens.ReviewScreen
import com.lexiflow.ui.screens.SettingsScreen
import com.lexiflow.ui.screens.ShadowingScreen
import com.lexiflow.ui.screens.SpeakingScreen
import com.lexiflow.ui.screens.StoriesScreen
import com.lexiflow.ui.screens.StoryReaderScreen
import com.lexiflow.ui.screens.WordDetailScreen
import com.lexiflow.ui.screens.WordEditScreen
import com.lexiflow.ui.screens.WordbookScreen

object Routes {
    const val HOME = "home"
    const val DECKS = "decks"
    const val PROGRESS = "progress"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val MODES = "modes"
    fun review(free: Boolean = false, deck: Long = -1) = "review?free=$free&deck=$deck"
    fun drill(mode: StudyMode) = "drill/${mode.name}"
    fun deck(id: Long) = "deck/$id"
    fun word(id: Long) = "word/$id"
    fun wordEdit(deck: Long = -1, word: Long = -1) = "wordedit?deck=$deck&word=$word"
    fun culture(kind: String) = "culture/$kind"
    fun cultureItem(id: Long) = "cultureitem/$id"
    fun stories(fc: Boolean) = "stories?fc=$fc"
    fun story(id: Long) = "story/$id"
    fun speaking(voice: Boolean) = "speaking/${if (voice) "VOICE" else "TEXT"}"
    fun lockQuestion(id: String) = "lockquestion/$id"
    const val DAILY_WORDS = "dailywords"
    const val IMPORT = "import"
    const val LOCKSCREEN = "lockscreen"
    const val PRONUNCIATION = "pronunciation"
    const val SHADOWING = "shadowing"
    const val BATTLE = "battle"
    const val WORDBOOK = "wordbook"
    const val SET_LIBRARY = "setlibrary"
    const val AUTOPILOT = "autopilot"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Ana sayfa", Icons.Default.Home),
    Tab(Routes.DECKS, "Desteler", Icons.Default.LibraryBooks),
    Tab(Routes.PROGRESS, "İlerleme", Icons.Default.BarChart),
    Tab(Routes.SETTINGS, "Ayarlar", Icons.Default.Settings),
)

@Composable
fun LexiFlowRoot(container: AppContainer, deepLink: String?, onDeepLinkHandled: () -> Unit) {
    val nav = rememberNavController()
    val settings by container.settings.settings.collectAsState(initial = null)
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    val loaded = settings ?: run { LoadingBox(); return }
    // Decide the start destination once, after settings are loaded (avoids navigating before the graph exists).
    val start = remember { if (loaded.onboardingDone) Routes.HOME else Routes.ONBOARDING }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (tabs.any { it.route == current }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { nav.navigate(t.route) { popUpTo(Routes.HOME); launchSingleTop = true } },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.testTag("tab_${t.route}"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Edge-to-edge: consume the bars Scaffold already padded for, then pad for the soft keyboard so text
        // fields are never hidden under it (adjustResize alone does nothing when drawing edge-to-edge).
        NavHost(
            nav, startDestination = start,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            enterTransition = { fadeIn(tween(180)) }, exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(180)) }, popExitTransition = { fadeOut(tween(120)) },
        ) {
            composable(Routes.HOME) { HomeScreen(container, nav) }
            composable(Routes.ONBOARDING) { OnboardingScreen(container) { nav.navigate(Routes.HOME) { popUpTo(0) } } }
            composable(Routes.MODES) { ModesScreen(nav) }
            composable(Routes.DECKS) { DecksScreen(container, nav) }
            composable(Routes.PROGRESS) { ProgressScreen(container, nav) }
            composable(Routes.SETTINGS) { SettingsScreen(container, nav) }
            composable(
                "review?free={free}&deck={deck}",
                arguments = listOf(navArgument("free") { type = NavType.BoolType; defaultValue = false }, navArgument("deck") { type = NavType.LongType; defaultValue = -1L }),
            ) { e -> ReviewScreen(container, nav, e.arguments!!.getBoolean("free"), e.arguments!!.getLong("deck").takeIf { it >= 0 }) }
            composable("drill/{mode}") { e -> DrillScreen(container, nav, StudyMode.valueOf(e.arguments!!.getString("mode")!!)) }
            composable(Routes.DAILY_WORDS) { DailyWordsScreen(container, nav) }
            composable("deck/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e -> DeckDetailScreen(container, nav, e.arguments!!.getLong("id")) }
            composable("word/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e -> WordDetailScreen(container, nav, e.arguments!!.getLong("id")) }
            composable(
                "wordedit?deck={deck}&word={word}",
                arguments = listOf(navArgument("deck") { type = NavType.LongType; defaultValue = -1L }, navArgument("word") { type = NavType.LongType; defaultValue = -1L }),
            ) { e -> WordEditScreen(container, nav, e.arguments!!.getLong("deck").takeIf { it >= 0 }, e.arguments!!.getLong("word").takeIf { it >= 0 }) }
            composable(Routes.WORDBOOK) { WordbookScreen(container, nav) }
            composable(Routes.AUTOPILOT) { com.lexiflow.ui.screens.AutopilotScreen(container, nav) }
            composable(Routes.SET_LIBRARY) { com.lexiflow.ui.screens.SetLibraryScreen(container, nav) }
            composable(Routes.IMPORT) { ImportScreen(container, nav) }
            composable(Routes.LOCKSCREEN) { LockScreenScreen(container, nav) }
            composable("lockquestion/{id}") { e -> LockQuestionScreen(container, nav, e.arguments!!.getString("id")!!) }
            composable("culture/{kind}") { e -> CultureScreen(container, nav, e.arguments!!.getString("kind")!!) }
            composable("cultureitem/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e -> CultureItemScreen(container, nav, e.arguments!!.getLong("id")) }
            composable("stories?fc={fc}", arguments = listOf(navArgument("fc") { type = NavType.BoolType; defaultValue = true })) { e -> StoriesScreen(container, nav, e.arguments!!.getBoolean("fc")) }
            composable("story/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e -> StoryReaderScreen(container, nav, e.arguments!!.getLong("id")) }
            composable("speaking/{mode}") { e -> SpeakingScreen(container, nav, e.arguments!!.getString("mode") == "VOICE") }
            composable(Routes.PRONUNCIATION) { PronunciationScreen(container, nav) }
            composable(Routes.SHADOWING) { ShadowingScreen(container, nav) }
            composable(Routes.BATTLE) { BattleScreen(container, nav) }
        }
        // Declared after NavHost inside the same (sub)composition, so the graph is set when this runs.
        LaunchedEffect(deepLink, loaded.onboardingDone) {
            val link = deepLink ?: return@LaunchedEffect
            if (!loaded.onboardingDone) return@LaunchedEffect
            runCatching { handleDeepLink(nav, link) }
            onDeepLinkHandled()
        }
    }
}

private fun handleDeepLink(nav: NavHostController, link: String) {
    val path = link.removePrefix("lexiflow://")
    when {
        path.startsWith("lockquestion/") -> nav.navigate(Routes.lockQuestion(path.removePrefix("lockquestion/")))
        path == "review" -> nav.navigate(Routes.review())
        path == "speaking" -> nav.navigate(Routes.speaking(true))
    }
}
