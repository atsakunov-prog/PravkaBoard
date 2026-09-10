@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.data.WordListWithCount
import ru.tsakunov.pravka.domain.isToday
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtDate
import ru.tsakunov.pravka.ui.lettersWord
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.vm.UpdateState
import ru.tsakunov.pravka.ui.wordsWord

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenList: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProgress: () -> Unit,
    onTab: (Tab) -> Unit,
) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    val attempts by vm.attempts.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<WordListWithCount?>(null) }

    val todayAttempts = remember(attempts) { attempts.filter { isToday(it.ts) } }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                title = { Text("Гармошка", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenProgress) { Icon(Icons.Filled.ShowChart, contentDescription = "Прогресс") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Настройки") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
        bottomBar = { PravkaBottomBar(Tab.PROPISI, onTab) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (todayAttempts.isNotEmpty()) {
                item {
                    PravkaCard {
                        Text("Сегодня", style = MaterialTheme.typography.labelMedium, color = PravkaColors.Muted)
                        Text(
                            "${wordsWord(todayAttempts.size)} · ${lettersWord(todayAttempts.sumOf { it.letters })}",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            }

            when (val u = update) {
                is UpdateState.Available -> item { UpdateBanner(u.info, progress = null, onAction = { vm.downloadUpdate(u.info) }, onDismiss = { vm.dismissUpdate() }) }
                is UpdateState.Downloading -> item { UpdateBanner(u.info, progress = u.progress, onAction = {}, onDismiss = null) }
                is UpdateState.Ready -> item { UpdateBanner(u.info, progress = 1f, onAction = { vm.installUpdate(u.file) }, onDismiss = { vm.dismissUpdate() }, ready = true) }
                else -> Unit
            }

            item { NewListCard(vm = vm, onCreated = onOpenList) }

            item { SectionTitle("Уроки") }

            if (lists.isEmpty()) {
                item { EmptyHint("Уроков пока нет. Сфотографируй страницу словаря или введи слова вручную.") }
            }

            items(lists, key = { it.id }) { list ->
                val doneToday = attempts.filter { it.listId == list.id && isToday(it.ts) && it.itemId != null }
                    .map { it.itemId to it.lang }.toSet().size
                val total = list.taskCount
                ListRow(
                    list = list,
                    doneToday = doneToday,
                    total = total,
                    onClick = { onOpenList(list.id) },
                    onDelete = { deleteTarget = list },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    deleteTarget?.let { t ->
        ConfirmDialog(
            title = "Удалить урок?",
            text = "«${t.title}» будет удалён. Результаты Бори по этим словам останутся в статистике.",
            onConfirm = { vm.deleteList(t.id) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ListRow(list: WordListWithCount, doneToday: Int, total: Int, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = PravkaColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, PravkaColors.Border),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(list.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${wordsWord(list.itemCount)} · ${fmtDate(list.createdAt)}",
                    style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                )
            }
            if (doneToday > 0) {
                val allDone = doneToday >= total
                Pill(
                    if (allDone) "готово" else "$doneToday/$total",
                    bg = if (allDone) PravkaColors.GoodSoft else PravkaColors.Surface2,
                    fg = if (allDone) PravkaColors.GoodText else PravkaColors.Ink2,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = PravkaColors.Muted)
            }
        }
    }
}

@Composable
private fun UpdateBanner(
    info: ru.tsakunov.pravka.api.UpdateInfo,
    progress: Float?,
    onAction: () -> Unit,
    onDismiss: (() -> Unit)?,
    ready: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = PravkaColors.EnSoft,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (ready) "Сборка ${info.buildNumber} скачана" else "Есть новая версия: ${info.versionName.ifBlank { "сборка" }} (сборка ${info.buildNumber})",
                style = MaterialTheme.typography.titleMedium, color = PravkaColors.EnText,
            )
            Spacer(Modifier.height(8.dp))
            when {
                ready -> BigButton("Установить", onClick = onAction, container = PravkaColors.En)
                progress != null -> {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = PravkaColors.En, trackColor = PravkaColors.Surface)
                    Text("Скачиваю… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = PravkaColors.EnText, modifier = Modifier.padding(top = 6.dp))
                }
                else -> BigButton("Обновить (${info.sizeBytes / 1_000_000} МБ)", onClick = onAction, container = PravkaColors.En)
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Позже", color = PravkaColors.EnText)
                }
            }
        }
    }
}
