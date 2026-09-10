package ru.tsakunov.pravka.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.tsakunov.pravka.PravkaApp
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.ui.components.Tab
import ru.tsakunov.pravka.ui.screens.HomeScreen
import ru.tsakunov.pravka.ui.screens.ListScreen
import ru.tsakunov.pravka.ui.screens.PracticeScreen
import ru.tsakunov.pravka.ui.screens.ProgressScreen
import ru.tsakunov.pravka.ui.screens.SettingsScreen
import ru.tsakunov.pravka.ui.theme.PravkaTheme
import ru.tsakunov.pravka.ui.vm.AppViewModel

object Routes {
    const val HOME = "home"
    const val PROGRESS = "progress"
    const val SETTINGS = "settings"
    const val LIST = "list/{listId}"
    const val PRACTICE = "practice/{listId}/{itemId}/{lang}"

    fun list(id: String) = "list/$id"
    fun practice(listId: String, itemId: String, lang: Lang) = "practice/$listId/$itemId/${lang.code}"
}

@Composable
fun PravkaRoot(app: PravkaApp) {
    val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(app))
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val toast by vm.toast.collectAsStateWithLifecycle()

    LaunchedEffect(toast) {
        val t = toast ?: return@LaunchedEffect
        vm.toastShown()
        snackbar.showSnackbar(t)
    }

    PravkaTheme {
        Box(Modifier.fillMaxSize()) {
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        vm = vm,
                        onOpenList = { nav.navigate(Routes.list(it)) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                        onTab = { nav.switchTab(it) },
                    )
                }
                composable(Routes.PROGRESS) {
                    ProgressScreen(vm = vm, onTab = { nav.switchTab(it) })
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
    val route = if (tab == Tab.WORDS) Routes.HOME else Routes.PROGRESS
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
