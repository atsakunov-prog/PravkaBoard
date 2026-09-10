package ru.tsakunov.pravka.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.tsakunov.pravka.PravkaApp
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.domain.IntakeKind
import ru.tsakunov.pravka.ui.components.Tab
import ru.tsakunov.pravka.ui.screens.GrammarDrillScreen
import ru.tsakunov.pravka.ui.screens.GrammarListScreen
import ru.tsakunov.pravka.ui.screens.GrammarSetScreen
import ru.tsakunov.pravka.ui.screens.HomeScreen
import ru.tsakunov.pravka.ui.screens.IntakeScreen
import ru.tsakunov.pravka.ui.screens.ReadingListScreen
import ru.tsakunov.pravka.ui.screens.ReadingScreen
import ru.tsakunov.pravka.ui.screens.HomeworkListScreen
import ru.tsakunov.pravka.ui.screens.HomeworkScreen
import ru.tsakunov.pravka.ui.screens.LearnScreen
import ru.tsakunov.pravka.ui.screens.ListScreen
import ru.tsakunov.pravka.ui.screens.StoryScreen
import ru.tsakunov.pravka.ui.screens.TeachScreen
import ru.tsakunov.pravka.ui.screens.TestScreen
import ru.tsakunov.pravka.ui.screens.WordsHubScreen
import ru.tsakunov.pravka.ui.screens.WordsScreen
import ru.tsakunov.pravka.ui.screens.PracticeScreen
import ru.tsakunov.pravka.ui.screens.ProgressScreen
import ru.tsakunov.pravka.ui.screens.SettingsScreen
import ru.tsakunov.pravka.ui.screens.StatsScreen
import ru.tsakunov.pravka.ui.theme.PravkaTheme
import ru.tsakunov.pravka.ui.vm.AppViewModel

object Routes {
    const val HOME = "home"
    const val WORDS = "words"
    const val GRAMMAR = "grammar"
    const val HOMEWORK = "homework"
    const val TEXTS = "texts"
    const val PROGRESS = "progress"
    const val SETTINGS = "settings"
    const val INTAKE = "intake"
    const val STATS = "stats"
    const val LIST = "list/{listId}"
    const val PRACTICE = "practice/{listId}/{itemId}/{lang}"
    const val WORDS_HUB = "words/{listId}"
    const val LEARN = "learn/{listId}"
    const val TEACH = "teach/{listId}"
    const val TEST = "test/{listId}"
    const val STORY = "story/{listId}"
    const val HOMEWORK_ITEM = "homework/{homeworkId}"
    const val GRAMMAR_SET = "grammar/{setId}"
    const val GRAMMAR_DRILL = "grammar/{setId}/{ruleIndex}"
    const val READING_ITEM = "texts/{textId}"

    fun list(id: String) = "list/$id"
    fun practice(listId: String, itemId: String, lang: Lang) = "practice/$listId/$itemId/${lang.code}"
    fun wordsHub(id: String) = "words/$id"
    fun learn(id: String) = "learn/$id"
    fun teach(id: String) = "teach/$id"
    fun test(id: String) = "test/$id"
    fun story(id: String) = "story/$id"
    fun homework(id: String) = "homework/$id"
    fun grammarSet(id: String) = "grammar/$id"
    fun grammarDrill(id: String, ruleIndex: Int) = "grammar/$id/$ruleIndex"
    fun reading(id: String) = "texts/$id"

    fun tabRoute(tab: Tab) = when (tab) {
        Tab.PROPISI -> HOME; Tab.WORDS -> WORDS; Tab.GRAMMAR -> GRAMMAR; Tab.HOMEWORK -> HOMEWORK; Tab.TEXT -> TEXTS; Tab.STATS -> STATS
    }
}

@Composable
fun PravkaRoot(app: PravkaApp) {
    val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(app))
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val toast by vm.toast.collectAsStateWithLifecycle()

    LaunchedEffect(toast) {
        val t = toast ?: return@LaunchedEffect
        snackbar.showSnackbar(t)
        vm.toastShown()
    }

    // Экран не гаснет на тренировке. Флаг держится здесь, а не внутри экрана: при переходе
    // «Дальше» старый экран тренировки исчезает уже после появления нового и сбросил бы флаг.
    val backStackEntry by nav.currentBackStackEntryAsState()
    val onPractice = backStackEntry?.destination?.route == Routes.PRACTICE
    val view = LocalView.current
    DisposableEffect(onPractice) {
        view.keepScreenOn = onPractice
        onDispose { view.keepScreenOn = false }
    }

    PravkaTheme {
        Box(Modifier.fillMaxSize()) {
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        vm = vm,
                        onOpenList = { nav.navigate(Routes.list(it)) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                        onOpenProgress = { nav.navigate(Routes.PROGRESS) },
                        onIntake = { nav.navigate(Routes.INTAKE) },
                        onTab = { nav.switchTab(it) },
                    )
                }
                composable(Routes.INTAKE) {
                    IntakeScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onOpenPart = { kind, id ->
                            nav.navigate(
                                when (kind) {
                                    IntakeKind.VOCABULARY -> Routes.list(id)
                                    IntakeKind.GRAMMAR -> Routes.grammarSet(id)
                                    IntakeKind.EXERCISE -> Routes.homework(id)
                                    IntakeKind.READING -> Routes.reading(id)
                                },
                            )
                        },
                    )
                }
                composable(Routes.PROGRESS) {
                    ProgressScreen(vm = vm, onBack = { nav.popBackStack() })
                }
                composable(Routes.STATS) {
                    StatsScreen(
                        vm = vm,
                        onOpenProgress = { nav.navigate(Routes.PROGRESS) },
                        onIntake = { nav.navigate(Routes.INTAKE) },
                        onTab = { nav.switchTab(it) },
                    )
                }
                composable(Routes.WORDS) {
                    WordsScreen(vm = vm, onOpenHub = { nav.navigate(Routes.wordsHub(it)) }, onIntake = { nav.navigate(Routes.INTAKE) }, onTab = { nav.switchTab(it) })
                }
                composable(Routes.WORDS_HUB) { entry ->
                    val listId = entry.arguments?.getString("listId") ?: return@composable
                    WordsHubScreen(
                        vm = vm, listId = listId,
                        onBack = { nav.popBackStack() },
                        onLearn = { nav.navigate(Routes.learn(listId)) },
                        onTeach = { nav.navigate(Routes.teach(listId)) },
                        onTest = { nav.navigate(Routes.test(listId)) },
                        onStory = { nav.navigate(Routes.story(listId)) },
                    )
                }
                composable(Routes.LEARN) { entry ->
                    val listId = entry.arguments?.getString("listId") ?: return@composable
                    LearnScreen(vm = vm, listId = listId, onBack = { nav.popBackStack() }, onTeach = {
                        nav.navigate(Routes.teach(listId)) { popUpTo(Routes.WORDS_HUB) { inclusive = false } }
                    })
                }
                composable(Routes.TEACH) { entry ->
                    val listId = entry.arguments?.getString("listId") ?: return@composable
                    TeachScreen(vm = vm, listId = listId, onBack = { nav.popBackStack() }, onTest = {
                        nav.navigate(Routes.test(listId)) { popUpTo(Routes.WORDS_HUB) { inclusive = false } }
                    })
                }
                composable(Routes.TEST) { entry ->
                    val listId = entry.arguments?.getString("listId") ?: return@composable
                    TestScreen(vm = vm, listId = listId, onBack = { nav.popBackStack() })
                }
                composable(Routes.STORY) { entry ->
                    val listId = entry.arguments?.getString("listId") ?: return@composable
                    StoryScreen(vm = vm, listId = listId, onBack = { nav.popBackStack() })
                }
                composable(Routes.GRAMMAR) {
                    GrammarListScreen(vm = vm, onOpen = { nav.navigate(Routes.grammarSet(it)) }, onIntake = { nav.navigate(Routes.INTAKE) }, onTab = { nav.switchTab(it) })
                }
                composable(Routes.GRAMMAR_SET) { entry ->
                    val id = entry.arguments?.getString("setId") ?: return@composable
                    GrammarSetScreen(vm = vm, setId = id, onBack = { nav.popBackStack() }, onDrill = { nav.navigate(Routes.grammarDrill(id, it)) })
                }
                composable(Routes.GRAMMAR_DRILL) { entry ->
                    val id = entry.arguments?.getString("setId") ?: return@composable
                    val ruleIndex = entry.arguments?.getString("ruleIndex")?.toIntOrNull() ?: return@composable
                    GrammarDrillScreen(vm = vm, setId = id, ruleIndex = ruleIndex, onBack = { nav.popBackStack() })
                }
                composable(Routes.HOMEWORK) {
                    HomeworkListScreen(vm = vm, onOpen = { nav.navigate(Routes.homework(it)) }, onIntake = { nav.navigate(Routes.INTAKE) }, onTab = { nav.switchTab(it) })
                }
                composable(Routes.HOMEWORK_ITEM) { entry ->
                    val id = entry.arguments?.getString("homeworkId") ?: return@composable
                    HomeworkScreen(vm = vm, homeworkId = id, onBack = { nav.popBackStack() })
                }
                composable(Routes.TEXTS) {
                    ReadingListScreen(vm = vm, onOpen = { nav.navigate(Routes.reading(it)) }, onIntake = { nav.navigate(Routes.INTAKE) }, onTab = { nav.switchTab(it) })
                }
                composable(Routes.READING_ITEM) { entry ->
                    val id = entry.arguments?.getString("textId") ?: return@composable
                    ReadingScreen(vm = vm, textId = id, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
                }
                composable(Routes.LIST) { entry ->
                    val listId = entry.arguments?.getString("listId") ?: return@composable
                    ListScreen(
                        vm = vm,
                        listId = listId,
                        onBack = { nav.popBackStack() },
                        onPractice = { itemId, lang -> nav.navigate(Routes.practice(listId, itemId, lang)) },
                    )
                }
                composable(Routes.PRACTICE) { entry ->
                    val args = entry.arguments ?: return@composable
                    val listId = args.getString("listId") ?: return@composable
                    val itemId = args.getString("itemId") ?: return@composable
                    val lang = Lang.of(args.getString("lang"))
                    PracticeScreen(
                        vm = vm,
                        listId = listId,
                        itemId = itemId,
                        lang = lang,
                        onBack = { nav.popBackStack() },
                        onNext = { nextItem, nextLang ->
                            nav.navigate(Routes.practice(listId, nextItem, nextLang)) {
                                popUpTo(Routes.LIST) { inclusive = false }
                            }
                        },
                        onAllDone = {
                            vm.showToast("Все слова списка написаны. Красавчик!")
                            nav.popBackStack()
                        },
                    )
                }
            }
            SnackbarHost(
                snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            )
        }
    }
}

private fun NavHostController.switchTab(tab: Tab) {
    val route = Routes.tabRoute(tab)
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
