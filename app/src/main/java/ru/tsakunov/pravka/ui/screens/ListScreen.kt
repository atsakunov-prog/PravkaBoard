@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.domain.Task
import ru.tsakunov.pravka.domain.attemptsForWord
import ru.tsakunov.pravka.domain.countLetters
import ru.tsakunov.pravka.domain.doneToday
import ru.tsakunov.pravka.domain.isToday
import ru.tsakunov.pravka.domain.repeatRowsForList
import ru.tsakunov.pravka.domain.repeatSummary
import ru.tsakunov.pravka.domain.tasksOf
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtNum
import ru.tsakunov.pravka.ui.fmtTime
import ru.tsakunov.pravka.ui.lettersWord
import ru.tsakunov.pravka.ui.plural
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.wordsWord

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
    val undoStack by vm.undoStack.collectAsStateWithLifecycle()
    val canUndo = undoStack.any { it.listId == listId }

    var editing by remember { mutableStateOf<WordItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    val tasks = remember(items) { tasksOf(items) }
    val todayHere = remember(attempts, listId) { attempts.filter { it.listId == listId && isToday(it.ts) } }
    val firstUndone = tasks.firstOrNull { doneToday(attempts, it) == null }
    val repeats = remember(attempts, items) { repeatRowsForList(attempts, listId, items) }

    // Перетаскивание за ручку: пока палец на строке, порядок живёт здесь и уходит в базу только при отпускании.
    val listState = rememberLazyListState()
    val drag = remember(listId) { DragReorder(listState) }
    val shown = drag.order ?: items
    // Room отдал новый порядок (или список изменился иначе): локальная копия больше не нужна.
    LaunchedEffect(items) { if (drag.key == null) drag.order = null }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                navigationIcon = { BackIcon(onBack) },
                title = {
                    Text(
                        list?.title ?: "", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { renaming = true },
                    )
                },
                actions = {
                    IconButton(onClick = { vm.undoLast(listId) }, enabled = canUndo) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo, contentDescription = "Отменить последнее действие",
                            tint = if (canUndo) PravkaColors.Ink else PravkaColors.Muted,
                        )
                    }
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Переименовать") }
                    IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Добавить слово") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
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

            if (repeats.isNotEmpty()) {
                item(key = "repeats") {
                    PravkaCard {
                        Text("Повторы: то же слово во второй раз", style = MaterialTheme.typography.titleMedium)
                        repeatSummary(repeats)?.let { RepeatSummaryText(it, Modifier.padding(top = 4.dp, bottom = 8.dp)) }
                        RepeatTable(repeats)
                        Text(
                            "Сюда попадают и результаты с бумаги по тем же словам.",
                            style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            items(shown, key = { it.id }) { item ->
                val dragging = drag.key == item.id
                val index = shown.indexOfFirst { it.id == item.id }
                WordRow(
                    item = item,
                    attempts = attempts,
                    dragging = dragging,
                    modifier = when {
                        // Перетаскиваемая строка едет за пальцем поверх соседей; соседи плавно расступаются
                        // (на E-Ink анимация только пачкает экран, там строки прыгают сразу).
                        dragging -> Modifier.zIndex(1f).graphicsLayer { translationY = drag.translation(item.id) }
                        PravkaColors.reader -> Modifier
                        else -> Modifier.animateItem()
                    },
                    handle = Modifier.pointerInput(item.id) {
                        detectDragGestures(
                            onDragStart = { drag.start(item.id, items) },
                            onDrag = { change, amount -> change.consume(); drag.move(amount.y) },
                            onDragEnd = { drag.finish()?.let { vm.reorderItems(listId, it, item.id) } },
                            onDragCancel = { drag.finish()?.let { vm.reorderItems(listId, it, item.id) } },
                        )
                    },
                    canMoveUp = index > 0,
                    canMoveDown = index >= 0 && index < shown.lastIndex,
                    onPractice = { lang -> onPractice(item.id, lang) },
                    onEdit = { editing = item },
                    onDelete = { vm.deleteItem(item) },
                    onMove = { delta -> vm.moveItem(listId, item.id, delta) },
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
    if (renaming) {
        RenameDialog(
            initial = list?.title ?: "",
            onSave = { vm.renameList(listId, it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
}

/**
 * Перетаскивание строки за ручку. Строка едет за пальцем (top — её верх в координатах списка), а когда её
 * середина заходит на соседа, слова меняются местами в локальном порядке; список сам перекладывает строки.
 * Список при этом не прокручивается: далёкие переезды делаются пунктами меню «Выше» и «Ниже».
 */
private class DragReorder(private val listState: LazyListState) {
    var key by mutableStateOf<String?>(null)
    var order by mutableStateOf<List<WordItem>?>(null)
    private var top by mutableFloatStateOf(0f)

    private fun info(k: Any?) = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }

    fun start(k: String, items: List<WordItem>) {
        key = k
        order = items
        top = info(k)?.offset?.toFloat() ?: 0f
    }

    fun move(dy: Float) {
        val k = key ?: return
        top += dy
        val me = info(k) ?: return
        val cur = order ?: return
        val center = top + me.size / 2f
        val ids = cur.map { it.id }.toHashSet()
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            val id = it.key as? String
            id != null && id != k && id in ids && center >= it.offset && center < it.offset + it.size
        } ?: return
        val from = cur.indexOfFirst { it.id == k }
        val to = cur.indexOfFirst { it.id == target.key }
        if (from < 0 || to < 0) return
        order = cur.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** На сколько строка под пальцем смещена от места, куда её положил список. */
    fun translation(k: String): Float = top - (info(k)?.offset ?: 0)

    /** Конец жеста: порядок идентификаторов для базы. Локальный порядок остаётся, пока Room не отдаст новый. */
    fun finish(): List<String>? {
        val cur = order
        key = null
        return cur?.map { it.id }
    }
}

@Composable
private fun WordRow(
    item: WordItem,
    attempts: List<Attempt>,
    dragging: Boolean,
    modifier: Modifier,
    handle: Modifier,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPractice: (Lang) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (dragging) PravkaColors.Surface2 else Color.Transparent,
        shadowElevation = if (dragging && !PravkaColors.reader) 6.dp else 0.dp,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(handle.width(28.dp).heightIn(min = 60.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.DragHandle, contentDescription = "Перетащить", tint = PravkaColors.Muted)
            }
            WordChip(
                text = item.en, lang = Lang.EN,
                done = if (item.en.isNotBlank()) doneToday(attempts, Task(item, Lang.EN)) else null,
                history = if (item.en.isNotBlank()) attemptsForWord(attempts, Lang.EN, item.en) else emptyList(),
                modifier = Modifier.weight(1f), onClick = { onPractice(Lang.EN) },
            )
            WordChip(
                text = item.ru, lang = Lang.RU,
                done = if (item.ru.isNotBlank()) doneToday(attempts, Task(item, Lang.RU)) else null,
                history = if (item.ru.isNotBlank()) attemptsForWord(attempts, Lang.RU, item.ru) else emptyList(),
                modifier = Modifier.weight(1f), onClick = { onPractice(Lang.RU) },
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Меню", tint = PravkaColors.Muted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Выше") }, leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) }, enabled = canMoveUp, onClick = { menu = false; onMove(-1) })
                    DropdownMenuItem(text = { Text("Ниже") }, leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) }, enabled = canMoveDown, onClick = { menu = false; onMove(+1) })
                    DropdownMenuItem(text = { Text("Изменить") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Удалить", color = PravkaColors.Danger) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = PravkaColors.Danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun WordChip(text: String, lang: Lang, done: Attempt?, history: List<Attempt>, modifier: Modifier, onClick: () -> Unit) {
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
                Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val best = history.minByOrNull { it.ms }
                Text(
                    when {
                        done != null -> "✓ ${fmtTime(done.ms)} · ${fmtNum(done.secPerLetter)} с/б"
                        best != null -> "${lettersWord(countLetters(text))} · лучшее ${fmtTime(best.ms)} · ${plural(history.size, "раз", "раза", "раз")}"
                        else -> lettersWord(countLetters(text))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done != null) PravkaColors.GoodText else PravkaColors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
