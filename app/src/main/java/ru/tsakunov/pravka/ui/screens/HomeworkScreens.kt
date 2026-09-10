@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.data.Homework
import ru.tsakunov.pravka.data.HomeworkCheck
import ru.tsakunov.pravka.domain.HomeworkResult
import ru.tsakunov.pravka.domain.HwItem
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtDate
import ru.tsakunov.pravka.ui.fmtDateTime
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.vm.HomeworkState

/** Вкладка «Домашка»: проверить новую и список проверенных. */
@Composable
fun HomeworkListScreen(vm: AppViewModel, onOpen: (String) -> Unit, onTab: (Tab) -> Unit) {
    val homeworks by vm.homeworks.collectAsStateWithLifecycle()
    val checks by vm.homeworkChecks.collectAsStateWithLifecycle()
    val state by vm.homeworkState.collectAsStateWithLifecycle()
    val picker = rememberPhotoPicker(onError = { vm.showToast(it) })
    var deleteTarget by remember { mutableStateOf<Homework?>(null) }

    LaunchedEffect(state) {
        val s = state
        if (s is HomeworkState.Done) {
            picker.clear()
            vm.homeworkHandled()
            onOpen(s.homeworkId)
        }
    }

    val firstTryPerfect = homeworks.count { hw -> checks.firstOrNull { it.homeworkId == hw.id && it.attemptNo == 1 }?.let { it.correct == it.total } == true }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                title = { Text("Домашка", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
        bottomBar = { PravkaBottomBar(Tab.HOMEWORK, onTab) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PravkaCard {
                    Text("Проверить домашку", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Сфотографируй страницу так, чтобы было видно и задание, и ответы. Opus отметит, где ошибка, но не скажет ответ: сначала Боря исправляет сам.",
                        style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2,
                    )
                    Spacer(Modifier.height(10.dp))
                    PhotoPickerControls(
                        picker = picker,
                        cameraLabel = "Сфотографировать домашку",
                        submitLabel = "Проверить (Opus)",
                        submitting = state is HomeworkState.Running,
                        onSubmit = { vm.checkHomework(picker.pending, homeworkId = null, previous = null) },
                    )
                }
            }
            if (homeworks.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Проверенные", Modifier.weight(1f))
                        if (firstTryPerfect > 0) Pill("без ошибок с первого раза: $firstTryPerfect", PravkaColors.GoldSoft, PravkaColors.GoldText)
                    }
                }
            }
            items(homeworks, key = { it.id }) { hw ->
                val own = checks.filter { it.homeworkId == hw.id }.sortedBy { it.attemptNo }
                val last = own.lastOrNull()
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(hw.id) },
                    shape = MaterialTheme.shapes.large,
                    color = PravkaColors.Surface,
                    border = BorderStroke(1.dp, PravkaColors.Border),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(hw.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            Text(
                                "${fmtDate(hw.updatedAt)} · ${own.size} ${if (own.size == 1) "проверка" else "проверки"}",
                                style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                            )
                        }
                        if (last != null) ScorePill(last.correct, last.total)
                        IconButton(onClick = { deleteTarget = hw }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = PravkaColors.Muted) }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    deleteTarget?.let { t ->
        ConfirmDialog(
            title = "Удалить домашку?", text = "«${t.title}» и все её проверки будут удалены.",
            onConfirm = { vm.deleteHomework(t.id) }, onDismiss = { deleteTarget = null },
        )
    }
    when (val s = state) {
        is HomeworkState.Running -> WorkingDialog("Opus проверяет домашку", s.photos)
        is HomeworkState.Error -> AlertDialog(
            onDismissRequest = { vm.homeworkHandled() },
            title = { Text("Не получилось") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.homeworkHandled() }) { Text("Понятно") } },
        )
        else -> Unit
    }
}

@Composable
private fun ScorePill(correct: Int, total: Int) {
    val perfect = total > 0 && correct == total
    Pill(
        "$correct / $total",
        bg = if (perfect) PravkaColors.GoodSoft else PravkaColors.Surface2,
        fg = if (perfect) PravkaColors.GoodText else PravkaColors.Ink2,
    )
}

/** Одна домашка: последняя проверка, история попыток, повторная проверка после исправлений. */
@Composable
fun HomeworkScreen(vm: AppViewModel, homeworkId: String, onBack: () -> Unit) {
    val homework by vm.observeHomework(homeworkId).collectAsStateWithLifecycle(initialValue = null)
    val checks by vm.observeHomeworkChecks(homeworkId).collectAsStateWithLifecycle(initialValue = emptyList())
    val state by vm.homeworkState.collectAsStateWithLifecycle()
    val picker = rememberPhotoPicker(onError = { vm.showToast(it) })
    var confetti by remember { mutableIntStateOf(0) }
    var celebratedFor by remember { mutableStateOf<String?>(null) }

    val last = checks.lastOrNull()
    val result = remember(last?.id) { last?.let { runCatching { HomeworkResult.fromJson(it.resultJson) }.getOrNull() } }

    LaunchedEffect(state) {
        val s = state
        if (s is HomeworkState.Done && s.homeworkId == homeworkId) {
            picker.clear()
            vm.homeworkHandled()
        }
    }
    // Конфетти один раз на каждую проверку без ошибок.
    LaunchedEffect(last?.id) {
        val l = last ?: return@LaunchedEffect
        if (l.total > 0 && l.correct == l.total && celebratedFor != l.id) {
            celebratedFor = l.id
            confetti++
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = PravkaColors.Page,
            topBar = {
                TopAppBar(
                    navigationIcon = { BackIcon(onBack) },
                    title = { Text(homework?.title ?: "Домашка", fontWeight = FontWeight.Bold, maxLines = 1) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (last == null || result == null) {
                    item { EmptyHint("Проверок пока нет") }
                    return@LazyColumn
                }
                item { ScoreHeader(last, checks) }
                result.exercises.forEach { ex ->
                    item {
                        PravkaCard {
                            Text(ex.name, style = MaterialTheme.typography.titleMedium)
                            if (ex.instruction.isNotBlank()) {
                                Text(ex.instruction, style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted)
                            }
                            Spacer(Modifier.height(8.dp))
                            ex.items.forEach { item -> HwItemRow(item, attemptNo = last.attemptNo) }
                        }
                    }
                }
                if (!result.allCorrect) {
                    item {
                        PravkaCard {
                            Text(
                                if (last.attemptNo == 1) "Исправь красные пункты и сфотографируй снова"
                                else "Исправь, что осталось, и сфотографируй ещё раз",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                when (last.attemptNo) {
                                    1 -> "Подсказки появятся, если ошибка останется после исправления."
                                    2 -> "Под ошибками уже есть подсказки. После следующей проверки откроются и правильные ответы."
                                    else -> "Правильные ответы открыты, перепиши и проверь ещё раз."
                                },
                                style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2,
                            )
                            Spacer(Modifier.height(10.dp))
                            PhotoPickerControls(
                                picker = picker,
                                cameraLabel = "Сфотографировать исправленное",
                                submitLabel = "Проверить ещё раз (Opus)",
                                submitting = state is HomeworkState.Running,
                                onSubmit = { vm.checkHomework(picker.pending, homeworkId = homeworkId, previous = result) },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
        ConfettiOverlay(trigger = confetti, modifier = Modifier.fillMaxSize())
    }

    when (val s = state) {
        is HomeworkState.Running -> WorkingDialog("Opus проверяет исправления", s.photos)
        is HomeworkState.Error -> AlertDialog(
            onDismissRequest = { vm.homeworkHandled() },
            title = { Text("Не получилось") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.homeworkHandled() }) { Text("Понятно") } },
        )
        else -> Unit
    }
}

@Composable
private fun ScoreHeader(last: HomeworkCheck, all: List<HomeworkCheck>) {
    val perfect = last.total > 0 && last.correct == last.total
    PravkaCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${last.correct} из ${last.total}",
                style = MaterialTheme.typography.headlineLarge,
                color = if (perfect) PravkaColors.GoodText else PravkaColors.Ink,
            )
            Text(
                when {
                    perfect && last.attemptNo == 1 -> "Всё верно с первого раза!"
                    perfect -> "Всё верно! Исправлено с ${last.attemptNo}-й попытки."
                    else -> "Попытка ${last.attemptNo}: осталось исправить ${last.total - last.correct}"
                },
                color = if (perfect) PravkaColors.GoodText else PravkaColors.Ink2, textAlign = TextAlign.Center,
            )
            if (all.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    all.joinToString(" · ") { "${it.attemptNo}-я: ${it.correct}/${it.total}" },
                    style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                )
            }
            Text(fmtDateTime(last.ts), style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted)
        }
    }
}

@Composable
private fun HwItemRow(item: HwItem, attemptNo: Int) {
    val bg = when {
        item.ok -> Color.Transparent
        item.missing -> PravkaColors.Surface2
        else -> Color(0xFFFBE3E3)
    }
    Column(Modifier.fillMaxWidth().background(bg, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.label, style = MaterialTheme.typography.labelLarge, color = PravkaColors.Muted, modifier = Modifier.width(28.dp))
            Text(
                if (item.missing) "не сделано" else item.answer.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium,
                color = when { item.ok -> PravkaColors.Ink; item.missing -> PravkaColors.Muted; else -> PravkaColors.Danger },
                modifier = Modifier.weight(1f),
            )
            Icon(
                when { item.ok -> Icons.Filled.Check; item.missing -> Icons.Filled.Remove; else -> Icons.Filled.Close },
                contentDescription = null,
                tint = when { item.ok -> PravkaColors.Good; item.missing -> PravkaColors.Muted; else -> PravkaColors.Danger },
            )
        }
        if (!item.ok && attemptNo >= 2 && item.hint.isNotBlank()) {
            Text("Подсказка: ${item.hint}", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2, modifier = Modifier.padding(start = 38.dp, top = 4.dp))
        }
        if (!item.ok && attemptNo >= 3 && item.expected.isNotBlank()) {
            Text("Правильно: ${item.expected}", style = MaterialTheme.typography.bodyMedium, color = PravkaColors.GoodText, modifier = Modifier.padding(start = 38.dp, top = 2.dp))
        }
    }
    Spacer(Modifier.height(4.dp))
}
