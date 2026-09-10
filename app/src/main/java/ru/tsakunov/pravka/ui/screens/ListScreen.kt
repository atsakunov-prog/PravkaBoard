@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.domain.countLetters
import ru.tsakunov.pravka.domain.isToday
import ru.tsakunov.pravka.domain.statsFor
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtNum
import ru.tsakunov.pravka.ui.fmtTime
import ru.tsakunov.pravka.ui.lettersWord
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.wordsWord

/** Слово в конкретном языке — единица тренировки. */
data class Task(val item: WordItem, val lang: Lang) {
    val text: String get() = if (lang == Lang.EN) item.en else item.ru
}

fun tasksOf(items: List<WordItem>): List<Task> = items.flatMap { it ->
    buildList {
        if (it.en.isNotBlank()) add(Task(it, Lang.EN))
        if (it.ru.isNotBlank()) add(Task(it, Lang.RU))
    }
}

fun doneToday(attempts: List<Attempt>, task: Task): Attempt? =
    attempts.filter { it.itemId == task.item.id && it.lang == task.lang.code && isToday(it.ts) }.maxByOrNull { it.ts }

@Composable
fun ListScreen(
    vm: AppViewModel,
    listId: String,
    onBack: () -> Unit,
    onPractice: (itemId: String, lang: Lang) -> Unit,
) {
    val list by vm.observeList(listId).collectAsStateWithLifecycle(initialValue = null)
    val items by vm.observeItems(listId).collectAsStateWithLifecycle(initialValue = emptyList())
    val attempts by vm.attempts.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<WordItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WordItem?>(null) }
    var renaming by remember { mutableStateOf(false) }

    val tasks = remember(items) { tasksOf(items) }
    val todayHere = remember(attempts, listId) { attempts.filter { it.listId == listId && isToday(it.ts) } }
    val firstUndone = tasks.firstOrNull { doneToday(attempts, it) == null }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                navigationIcon = { BackIcon(onBack) },
                title = {
                    Text(
                        list?.title ?: "", fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.clickable { renaming = true },
                    )
                },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Переименовать") }
                    IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Добавить слово") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                PravkaCard {
                    if (todayHere.isNotEmpty()) {
                        val letters = todayHere.sumOf { it.letters }
                        val spl = todayHere.sumOf { it.ms } / 1000.0 / letters
                        Text("Сегодня по этому списку", style = MaterialTheme.typography.labelMedium, color = PravkaColors.Muted)
                        Text(
                            "${wordsWord(todayHere.size)} из ${tasks.size} · ${lettersWord(letters)} · ${fmtNum(spl)} с/букву",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    if (firstUndone != null) {
                        BigButton(
                            if (todayHere.isEmpty()) "Начать писать" else "Продолжить",
                            onClick = { onPractice(firstUndone.item.id, firstUndone.lang) },
                            container = PravkaColors.Good,
                        )
                        Text(
                            "Или нажми на любое слово ниже",
                            style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                            modifier = Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally),
                        )
                    } else if (tasks.isNotEmpty()) {
                        Text("Все слова написаны сегодня. Молодец, Боря!", style = MaterialTheme.typography.titleMedium, color = PravkaColors.GoodText)
                        Text("Можно повторить любое слово, нажав на него.", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted)
                    } else {
                        Text("В списке пока нет слов. Добавь через плюс сверху.", color = PravkaColors.Muted)
                    }
                }
            }

            items(items, key = { it.id }) { item ->
                WordRow(
                    item = item,
                    attempts = attempts,
                    onPractice = { lang -> onPractice(item.id, lang) },
                    onEdit = { editing = item },
                    onDelete = { deleting = item },
                )
            }
        }
    }

    editing?.let { it ->
        ItemDialog(
            title = "Изменить слово", initialEn = it.en, initialRu = it.ru,
            onSave = { en, ru -> vm.updateItem(it, en, ru); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (adding) {
        ItemDialog(
            title = "Новое слово", initialEn = "", initialRu = "",
            onSave = { en, ru -> vm.addItem(listId, en, ru); adding = false },
            onDismiss = { adding = false },
        )
    }
    deleting?.let { it ->
        ConfirmDialog(
            title = "Удалить слово?", text = "«${it.en.ifBlank { it.ru }}» исчезнет из списка.",
            onConfirm = { vm.deleteItem(it) }, onDismiss = { deleting = null },
        )
    }
    if (renaming) {
        RenameDialog(
            initial = list?.title ?: "",
            onSave = { vm.renameList(listId, it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun WordRow(
    item: WordItem,
    attempts: List<Attempt>,
    onPractice: (Lang) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WordChip(
            text = item.en, lang = Lang.EN,
            done = if (item.en.isNotBlank()) doneToday(attempts, Task(item, Lang.EN)) else null,
            modifier = Modifier.weight(1f), onClick = { onPractice(Lang.EN) },
        )
        WordChip(
            text = item.ru, lang = Lang.RU,
            done = if (item.ru.isNotBlank()) doneToday(attempts, Task(item, Lang.RU)) else null,
            modifier = Modifier.weight(1f), onClick = { onPractice(Lang.RU) },
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Меню", tint = PravkaColors.Muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Изменить") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Удалить", color = PravkaColors.Danger) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = PravkaColors.Danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun WordChip(text: String, lang: Lang, done: Attempt?, modifier: Modifier, onClick: () -> Unit) {
    val enabled = text.isNotBlank()
    Surface(
        modifier = modifier.heightIn(min = 60.dp).clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = when {
            !enabled -> PravkaColors.Surface2
            done != null -> PravkaColors.GoodSoft
            else -> PravkaColors.Surface
        },
        border = BorderStroke(1.5.dp, if (done != null) PravkaColors.Good.copy(alpha = 0.5f) else lang.softColor()),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            if (!enabled) {
                Text("—", color = PravkaColors.Muted)
            } else {
                Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    if (done != null) "✓ ${fmtTime(done.ms)} · ${fmtNum(done.secPerLetter)} с/б" else lettersWord(countLetters(text)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done != null) PravkaColors.GoodText else PravkaColors.Muted,
                )
            }
        }
    }
}

@Suppress("unused")
private fun bestFor(lang: Lang, attempts: List<Attempt>) = statsFor(lang, attempts).best
