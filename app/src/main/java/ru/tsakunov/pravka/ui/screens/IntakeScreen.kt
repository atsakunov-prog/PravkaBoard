@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.tsakunov.pravka.data.IntakeJob
import ru.tsakunov.pravka.domain.IntakeKind
import ru.tsakunov.pravka.domain.IntakePart
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtDateTime
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.vm.IntakeState

/**
 * Вся домашка одним пакетом фото: Opus раскладывает страницы по разделам (словарь, правило, упражнения,
 * текст), каждый раздел разбирается своим способом. Переключатель пакетной обработки экономит половину
 * стоимости ценой ожидания.
 */
@Composable
fun IntakeScreen(vm: AppViewModel, onBack: () -> Unit, onOpenPart: (IntakeKind, String) -> Unit) {
    val jobs by vm.intake.jobs.collectAsStateWithLifecycle()
    val state by vm.intake.state.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val picker = rememberPhotoPicker(maxItems = 24, onError = { vm.showToast(it) })
    var deleteTarget by remember { mutableStateOf<IntakeJob?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state) {
        val s = state
        if (s is IntakeState.Done) {
            picker.clear()
            expanded = s.jobId
            vm.showToast(if (s.queued) "Пакет отправлен. Проверяю каждые полминуты, можно закрыть приложение." else "Готово, разложил по вкладкам")
            vm.intake.handled()
        }
    }
    // Пока на экране есть отправленные пакеты, опрашиваем их раз в полминуты.
    val hasQueued = jobs.any { it.status == IntakeJob.QUEUED }
    LaunchedEffect(hasQueued) {
        while (hasQueued) { vm.intake.refresh(); delay(30_000) }
    }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                navigationIcon = { BackIcon(onBack) },
                title = { Text("Вся домашка", fontWeight = FontWeight.Bold) },
                actions = {
                    if (hasQueued) IconButton(onClick = { vm.intake.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Проверить пакеты") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PravkaCard {
                    Text("Сфотографируй всё подряд", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Словарь, правило, упражнения с ответами, страницы книжки — до 24 фото в любом порядке. Opus сам поймёт, где что, и разложит по вкладкам: Гармошка и Слова, Грамматика, Домашка, Текст.",
                        style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2,
                    )
                    Spacer(Modifier.height(10.dp))
                    PhotoPickerControls(
                        picker = picker,
                        cameraLabel = "Сфотографировать страницу",
                        submitLabel = if (settings.batchMode) "Отправить пакетом (−50%)" else "Разобрать сейчас (Opus)",
                        submitting = state is IntakeState.Sorting || state is IntakeState.Running || state is IntakeState.Submitting,
                        onSubmit = { vm.intake.run(picker.pending, settings.batchMode) },
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = PravkaColors.Grid)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Пакетная обработка", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (settings.batchMode) "Вдвое дешевле. Ответ обычно через 5–30 минут, по договору до суток. Сортировка страниц всё равно идёт сразу."
                                else "Выключена: результат через минуту, полная цена.",
                                style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                            )
                        }
                        Switch(checked = settings.batchMode, onCheckedChange = { vm.setBatchMode(it) })
                    }
                }
            }
            if (jobs.isNotEmpty()) item { SectionTitle("Разборы") }
            items(jobs, key = { it.id }) { job ->
                JobCard(
                    job = job,
                    expanded = expanded == job.id || job.status != IntakeJob.DONE,
                    onToggle = { expanded = if (expanded == job.id) null else job.id },
                    onOpenPart = onOpenPart,
                    onDelete = { deleteTarget = job },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    deleteTarget?.let { t ->
        ConfirmDialog(
            title = "Убрать разбор из списка?",
            text = "Созданные уроки, темы, домашки и тексты останутся на своих вкладках.",
            onConfirm = { vm.intake.delete(t.id) }, onDismiss = { deleteTarget = null },
        )
    }
    when (val s = state) {
        is IntakeState.Sorting -> WorkingDialog("Opus смотрит, что на страницах", s.photos)
        is IntakeState.Submitting -> WorkingDialog("Отправляю пакет из ${s.parts} запросов", 1)
        is IntakeState.Running -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Opus разбирает разделы") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth(), color = PravkaColors.En, trackColor = PravkaColors.Surface2)
                    Spacer(Modifier.height(12.dp))
                    Text("Готово ${s.done} из ${s.total}. Разделы идут параллельно, обычно 30–90 секунд.", style = MaterialTheme.typography.bodyMedium, color = PravkaColors.Ink2)
                }
            },
        )
        is IntakeState.Error -> AlertDialog(
            onDismissRequest = { vm.intake.handled() },
            title = { Text("Не получилось") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.intake.handled() }) { Text("Понятно") } },
        )
        else -> Unit
    }
}

@Composable
private fun JobCard(job: IntakeJob, expanded: Boolean, onToggle: () -> Unit, onOpenPart: (IntakeKind, String) -> Unit, onDelete: () -> Unit) {
    val parts = remember(job.partsJson) { IntakePart.listFromJson(job.partsJson) }
    val done = parts.count { it.done }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = MaterialTheme.shapes.large,
        color = PravkaColors.Surface,
        border = BorderStroke(1.dp, PravkaColors.Border),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(
                        "${fmtDateTime(job.createdAt)} · ${job.photos} фото · " + (if (job.isBatch) "пакетом" else "сразу"),
                        style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                    )
                }
                when (job.status) {
                    IntakeJob.QUEUED -> Pill("ждём", PravkaColors.GoldSoft, PravkaColors.GoldText)
                    IntakeJob.RUNNING -> Pill("идёт", PravkaColors.EnSoft, PravkaColors.EnText)
                    IntakeJob.ERROR -> Pill("ошибка", PravkaColors.Surface2, PravkaColors.Danger)
                    else -> Pill("$done / ${parts.size}", PravkaColors.GoodSoft, PravkaColors.GoodText)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Убрать", tint = PravkaColors.Muted) }
            }
            if (job.status == IntakeJob.QUEUED) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp, end = 12.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = PravkaColors.Gold)
                    Text(job.progress ?: "в очереди Anthropic", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2)
                }
            }
            if (job.status == IntakeJob.ERROR && job.error != null) {
                Text(job.error, style = MaterialTheme.typography.bodySmall, color = PravkaColors.Danger, modifier = Modifier.padding(top = 6.dp, end = 12.dp))
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                parts.forEach { p -> PartRow(p, job.status == IntakeJob.QUEUED, onOpen = { id -> onOpenPart(p.kind, id) }) }
            }
        }
    }
}

@Composable
private fun PartRow(p: IntakePart, queued: Boolean, onOpen: (String) -> Unit) {
    val target = p.targetId
    val clickable = p.done && target != null
    Row(
        Modifier.fillMaxWidth().padding(end = 12.dp)
            .background(if (clickable) PravkaColors.Surface2 else PravkaColors.Surface, MaterialTheme.shapes.small)
            .then(if (clickable) Modifier.clickable { onOpen(target!!) } else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(28.dp).background(
                when {
                    p.done -> PravkaColors.GoodSoft
                    p.failed -> PravkaColors.Surface2
                    else -> PravkaColors.GoldSoft
                },
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                p.done -> Icon(Icons.Filled.Check, contentDescription = null, tint = PravkaColors.Good, modifier = Modifier.size(16.dp))
                p.failed -> Icon(Icons.Filled.Close, contentDescription = null, tint = PravkaColors.Danger, modifier = Modifier.size(16.dp))
                else -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = PravkaColors.Gold)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                p.kind.label + " · " + (if (p.pages.size == 1) "фото ${p.pages[0]}" else "фото ${p.pages.joinToString(", ")}"),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    p.done -> p.title ?: "готово"
                    p.failed -> p.error ?: "не получилось"
                    queued -> "в пакете"
                    else -> "разбирается…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (p.failed) PravkaColors.Danger else PravkaColors.Ink2, maxLines = 2,
            )
        }
        if (clickable) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Открыть", tint = PravkaColors.Muted)
    }
    Spacer(Modifier.height(4.dp))
}
