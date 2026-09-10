@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.domain.normalizeWord
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.vm.StoryState

/** Рассказ на словах урока: сочинить, почитать с подсветкой слов, послушать, показать перевод. */
@Composable
fun StoryScreen(vm: AppViewModel, listId: String, onBack: () -> Unit) {
    val items by vm.observeItems(listId).collectAsStateWithLifecycle(initialValue = emptyList())
    val story by vm.observeStory(listId).collectAsStateWithLifecycle(initialValue = null)
    val state by vm.storyState.collectAsStateWithLifecycle()
    val speaker = rememberSpeaker()
    var showRu by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                navigationIcon = { BackIcon { speaker.stop(); onBack() } },
                title = { Text(story?.title ?: "Рассказ", fontWeight = FontWeight.Bold, maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = story
            if (s == null) {
                PravkaCard {
                    Text("Рассказа пока нет", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Opus сочинит смешную историю на 70–110 слов, где встретятся все английские слова урока. Обычно это 15–30 секунд.",
                        color = PravkaColors.Ink2,
                    )
                    Spacer(Modifier.height(12.dp))
                    BigButton(
                        if (state is StoryState.Generating) "Сочиняю…" else "Сочинить рассказ (Opus)",
                        onClick = { vm.generateStory(listId, items, null) },
                        container = PravkaColors.Good,
                        enabled = state !is StoryState.Generating,
                    )
                }
            } else {
                PravkaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("English", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { speaker.speak(s.textEn, slow = true) }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Прочитать вслух", tint = PravkaColors.EnText)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    HighlightedText(s.textEn, items.map { it.en })
                }
                PravkaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Перевод", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(checked = showRu, onCheckedChange = { showRu = it })
                    }
                    if (showRu) {
                        Spacer(Modifier.height(6.dp))
                        Text(s.textRu, style = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, color = PravkaColors.Ink2))
                    } else {
                        Text("Сначала попробуй понять без перевода.", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted)
                    }
                }
                SecondaryButton(
                    if (state is StoryState.Generating) "Сочиняю…" else "Сочинить другой рассказ",
                    onClick = { vm.generateStory(listId, items, s.title) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state !is StoryState.Generating,
                )
            }
            (state as? StoryState.Error)?.let {
                Text(it.message, color = PravkaColors.Danger)
            }
            if (state is StoryState.Generating) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = PravkaColors.Good)
                    Text("Opus придумывает историю…", color = PravkaColors.Ink2)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Текст с подсветкой слов урока. Ищем нормализованное слово (без артикля) по границам слов. */
@Composable
private fun HighlightedText(text: String, words: List<String>) {
    val keys = remember(words) { words.mapNotNull { normalizeWord(it) }.filter { it.isNotBlank() }.sortedByDescending { it.length } }
    val annotated = remember(text, keys) {
        val lower = text.lowercase()
        val marks = BooleanArray(text.length)
        for (k in keys) {
            var from = 0
            while (true) {
                val i = lower.indexOf(k, from)
                if (i < 0) break
                val before = if (i == 0) ' ' else lower[i - 1]
                val afterIdx = i + k.length
                val after = if (afterIdx >= lower.length) ' ' else lower[afterIdx]
                // допускаем окончание -s/-ed/-ing после основы
                val okBefore = !before.isLetter()
                var end = afterIdx
                while (end < lower.length && lower[end].isLetter()) end++
                val tail = lower.substring(afterIdx, end)
                val okAfter = !after.isLetter() || tail in setOf("s", "es", "ed", "ing", "d")
                if (okBefore && okAfter) for (j in i until end) marks[j] = true
                from = i + k.length
            }
        }
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val m = marks[i]
                var j = i
                while (j < text.length && marks[j] == m) j++
                if (m) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = PravkaColors.EnText)) { append(text.substring(i, j)) }
                else append(text.substring(i, j))
                i = j
            }
        }
    }
    Text(annotated, style = TextStyle(fontSize = 19.sp, lineHeight = 30.sp, color = PravkaColors.Ink))
}
