@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.domain.countWords
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtDate
import ru.tsakunov.pravka.ui.plural
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.vm.ReadingState
import kotlin.math.roundToInt

/** Единый вид текста для чтения: страница с фото или рассказ Opus. */
data class Readable(val id: String, val title: String, val textEn: String, val textRu: String, val words: Int, val createdAt: Long, val isStory: Boolean)

/** Вкладка «Текст»: страницы книжки и рассказы, у каждого лучшая скорость. */
@Composable
fun ReadingListScreen(vm: AppViewModel, onOpen: (String) -> Unit, onTab: (Tab) -> Unit) {
    val texts by vm.readingTexts.collectAsStateWithLifecycle()
    val stories by vm.allStories.collectAsStateWithLifecycle()
    val runs by vm.readingRuns.collectAsStateWithLifecycle()
    val state by vm.readingState.collectAsStateWithLifecycle()
    val picker = rememberPhotoPicker(maxItems = 20, onError = { vm.showToast(it) })
    var deleteTarget by remember { mutableStateOf<Readable?>(null) }

    val all = remember(texts, stories) {
        (texts.map { Readable(it.id, it.title, it.textEn, it.textRu, it.words, it.createdAt, false) } +
            stories.map { Readable(it.id, it.title, it.textEn, it.textRu, countWords(it.textEn), it.createdAt, true) })
            .sortedByDescending { it.createdAt }
    }

    LaunchedEffect(state) {
        val s = state
        if (s is ReadingState.Done) { picker.clear(); vm.readingHandled(); onOpen(s.textId) }
    }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                title = { Text("Текст", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
        bottomBar = { PravkaBottomBar(Tab.TEXT, onTab) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PravkaCard {
                    Text("Новый текст", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Сфотографируй страницы книжки (до 20 фото). Потом Боря читает вслух с микрофоном и переводит каждое предложение: Opus отметит, где прочитано верно и где перевод точный, а потом сравним со следующим разом.",
                        style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2,
                    )
                    Spacer(Modifier.height(10.dp))
                    PhotoPickerControls(
                        picker = picker,
                        cameraLabel = "Сфотографировать страницы",
                        submitLabel = "Распознать текст (Opus)",
                        submitting = state is ReadingState.Running,
                        onSubmit = { vm.extractReading(picker.pending) },
                    )
                }
            }
            if (all.isNotEmpty()) item { SectionTitle("Тексты") }
            items(all, key = { it.id }) { t ->
                val own = runs.filter { it.textId == t.id }
                val best = own.maxByOrNull { it.wordsPerMinute }
                val lastMic = own.lastOrNull { it.isMic && it.transOk != null && it.sentences != null }
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(t.id) },
                    shape = MaterialTheme.shapes.large,
                    color = PravkaColors.Surface,
                    border = BorderStroke(1.dp, PravkaColors.Border),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                                if (t.isStory) Pill("рассказ", PravkaColors.GoodSoft, PravkaColors.GoodText)
                            }
                            Text(
                                "${plural(t.words, "слово", "слова", "слов")} · ${fmtDate(t.createdAt)}" +
                                    (if (own.isNotEmpty()) " · читали ${own.size} раз" else ""),
                                style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (best != null) Pill("${best.wordsPerMinute.roundToInt()} сл/мин", PravkaColors.EnSoft, PravkaColors.EnText)
                            if (lastMic != null) Pill("перевод ${lastMic.transOk}/${lastMic.sentences}", PravkaColors.RuSoft, PravkaColors.RuText)
                        }
                        IconButton(onClick = { deleteTarget = t }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = PravkaColors.Muted) }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    deleteTarget?.let { t ->
        ConfirmDialog(
            title = if (t.isStory) "Удалить рассказ?" else "Удалить текст?",
            text = if (t.isStory) "«${t.title}» исчезнет и отсюда, и из урока. Если это был единственный рассказ урока, можно сочинить новый."
            else "«${t.title}» будет удалён вместе с результатами чтений.",
            onConfirm = { if (t.isStory) vm.deleteStory(t.id) else vm.deleteReadingText(t.id) },
            onDismiss = { deleteTarget = null },
        )
    }
    when (val s = state) {
        is ReadingState.Running -> WorkingDialog("Opus переписывает страницы", s.photos)
        is ReadingState.Error -> AlertDialog(
            onDismissRequest = { vm.readingHandled() },
            title = { Text("Не получилось") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.readingHandled() }) { Text("Понятно") } },
        )
        else -> Unit
    }
}

