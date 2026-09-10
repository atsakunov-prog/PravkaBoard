@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.data.QuizRun
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtDate
import ru.tsakunov.pravka.ui.fmtTime
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.wordsWord

/** Вкладка «Слова»: выбор урока, затем режимы. */
@Composable
fun WordsScreen(vm: AppViewModel, onOpenHub: (String) -> Unit, onTab: (Tab) -> Unit) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    val runs by vm.quizRuns.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                title = { Text("Слова", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
        bottomBar = { PravkaBottomBar(Tab.WORDS, onTab) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Выбери урок. Внутри: обучалка с карточками, училка, контроша с микрофоном и рассказ на этих словах.",
                    style = MaterialTheme.typography.bodyMedium, color = PravkaColors.Ink2,
                )
            }
            if (lists.isEmpty()) {
                item { NewListCard(vm = vm, onCreated = onOpenHub) }
            }
            items(lists, key = { it.id }) { list ->
                val best = runs.filter { it.listId == list.id }.minWithOrNull(compareBy<QuizRun>({ it.attempts }, { it.durationMs }))
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenHub(list.id) },
                    shape = MaterialTheme.shapes.large,
                    color = PravkaColors.Surface,
                    border = BorderStroke(1.dp, PravkaColors.Border),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(list.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${wordsWord(list.itemCount)} · ${fmtDate(list.createdAt)}" +
                                    (best?.let { " · контроша: с ${it.attempts}-й попытки за ${fmtTime(it.durationMs)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                            )
                        }
                        if (best != null) Pill("пройдена", PravkaColors.GoodSoft, PravkaColors.GoodText)
                    }
                }
            }
            if (lists.isNotEmpty()) item { NewListCard(vm = vm, onCreated = onOpenHub) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** Экран урока во вкладке «Слова»: четыре режима. */
@Composable
fun WordsHubScreen(
    vm: AppViewModel,
    listId: String,
    onBack: () -> Unit,
    onLearn: () -> Unit,
    onTeach: () -> Unit,
    onTest: () -> Unit,
    onStory: () -> Unit,
) {
    val list by vm.observeList(listId).collectAsStateWithLifecycle(initialValue = null)
    val items by vm.observeItems(listId).collectAsStateWithLifecycle(initialValue = emptyList())
    val runs by vm.observeQuizRuns(listId).collectAsStateWithLifecycle(initialValue = emptyList())
    val story by vm.observeStory(listId).collectAsStateWithLifecycle(initialValue = null)
    val pairs = items.count { it.en.isNotBlank() && it.ru.isNotBlank() }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                navigationIcon = { BackIcon(onBack) },
                title = { Text(list?.title ?: "", fontWeight = FontWeight.Bold, maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${wordsWord(pairs)} с переводом", color = PravkaColors.Ink2)
            ModeCard(
                icon = Icons.Filled.Style, color = PravkaColors.En, soft = PravkaColors.EnSoft,
                title = "Обучалка", text = "Карточки: слово и перевод вместе, смахивай и слушай, как звучит.",
                enabled = pairs > 0, onClick = onLearn,
            )
            ModeCard(
                icon = Icons.Filled.Visibility, color = PravkaColors.Violet, soft = Color(0xFFE6E2F8),
                title = "Училка", text = "Показывается русское слово, нажми и увидишь английское. Отмечай: знал или ещё повторить.",
                enabled = pairs > 0, onClick = onTeach,
            )
            ModeCard(
                icon = Icons.Filled.Mic, color = PravkaColors.Ru, soft = PravkaColors.RuSoft,
                title = "Контроша", text = "Русское слово на экране, говори по-английски в микрофон. Ошибка — и всё с начала." +
                    (runs.minByOrNull { it.attempts }?.let { " Лучший результат: с ${it.attempts}-й попытки." } ?: ""),
                enabled = pairs > 0, onClick = onTest,
            )
            ModeCard(
                icon = Icons.AutoMirrored.Filled.MenuBook, color = PravkaColors.Good, soft = PravkaColors.GoodSoft,
                title = "Рассказ", text = story?.let { "«${it.title}» уже сочинён. Можно почитать, послушать или заказать новый." }
                    ?: "Opus сочинит смешную историю, где встретятся все слова урока.",
                enabled = items.any { it.en.isNotBlank() }, onClick = onStory,
            )
        }
    }
}

@Composable
private fun ModeCard(icon: ImageVector, color: Color, soft: Color, title: String, text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = PravkaColors.Surface,
        border = BorderStroke(1.dp, PravkaColors.Border),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(soft, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = color)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) PravkaColors.Ink else PravkaColors.Muted)
                Text(text, style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2)
            }
        }
    }
}
