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
import ru.tsakunov.pravka.ui.components.Tab
import ru.tsakunov.pravka.ui.screens.HomeScreen
import ru.tsakunov.pravka.ui.screens.LearnScreen
import ru.tsakunov.pravka.ui.screens.ListScreen
import ru.tsakunov.pravka.ui.screens.PlaceholderScreen
import ru.tsakunov.pravka.ui.screens.StoryScreen
import ru.tsakunov.pravka.ui.screens.TeachScreen
import ru.tsakunov.pravka.ui.screens.TestScreen
import ru.tsakunov.pravka.ui.screens.WordsHubScreen
import ru.tsakunov.pravka.ui.screens.WordsScreen
import ru.tsakunov.pravka.ui.screens.PracticeScreen
import ru.tsakunov.pravka.ui.screens.ProgressScreen
import ru.tsakunov.pravka.ui.screens.SettingsScreen
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
    const val LIST = "list/{listId}"
    const val PRACTICE = "practice/{listId}/{itemId}/{lang}"
    const val WORDS_HUB = "words/{listId}"
    const val LEARN = "learn/{listId}"
    const val TEACH = "teach/{listId}"
    const val TEST = "test/{listId}"
    const val STORY = "story/{listId}"

    fun list(id: String) = "list/$id"
    fun practice(listId: String, itemId: String, lang: Lang) = "practice/$listId/$itemId/${lang.code}"
    fun wordsHub(id: String) = "words/$id"
    fun learn(id: String) = "learn/$id"
    fun teach(id: String) = "teach/$id"
    fun test(id: String) = "test/$id"
    fun story(id: String) = "story/$id"

    fun tabRoute(tab: Tab) = when (tab) {
        Tab.PROPISI -> HOME; Tab.WORDS -> WORDS; Tab.GRAMMAR -> GRAMMAR; Tab.HOMEWORK -> HOMEWORK; Tab.TEXT -> TEXTS
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
                        onTab = { nav.switchTab(it) },
                    )
                }
                composable(Routes.PROGRESS) {
                    ProgressScreen(vm = vm, onBack = { nav.popBackStack() })
                }
                composable(Routes.WORDS) {
                    WordsScreen(vm = vm, onOpenHub = { nav.navigate(Routes.wordsHub(it)) }, onTab = { nav.switchTab(it) })
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
                    PlaceholderScreen(Tab.GRAMMAR, "Фото страницы с правилом превратится в тренажёр: слово на экране, Боря говорит форму, папа отмечает верно или нет. Уровни по правилам, пять верных подряд открывают следующий.", onTab = { nav.switchTab(it) })
                }
                composable(Routes.HOMEWORK) {
                    PlaceholderScreen(Tab.HOMEWORK, "Фото сделанной домашки: Opus проверит и подсветит красным, что не так. После исправления второе фото, а если ошибка осталась, появится подсказка с объяснением.", onTab = { nav.switchTab(it) })
                }
                composable(Routes.TEXTS) {
                    PlaceholderScreen(Tab.TEXT, "Фото страниц книжки: чтение на время, слова в минуту, кнопка запинки, прогресс по книге. Сюда же попадут рассказы Opus на словах урока.", onTab = { nav.switchTab(it) })
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
