@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.Metric
import ru.tsakunov.pravka.domain.LangStats
import ru.tsakunov.pravka.domain.isToday
import ru.tsakunov.pravka.domain.statsFor
import ru.tsakunov.pravka.ui.*
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ProgressScreen(vm: AppViewModel, onTab: (Tab) -> Unit) {
    val attempts by vm.attempts.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val metric = settings.metric

    var showPaper by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Attempt?>(null) }

    val totalLetters = attempts.sumOf { it.letters }
    val today = attempts.filter { isToday(it.ts) }
    val en = remember(attempts) { statsFor(Lang.EN, attempts) }
    val ru = remember(attempts) { statsFor(Lang.RU, attempts) }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                title = { Text("Прогресс", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
        bottomBar = { PravkaBottomBar(Tab.PROGRESS, onTab) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PravkaCard {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            totalLetters.toString(),
                            style = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.Ink, letterSpacing = (-1).sp),
                        )
                        Text("букв написано за всё время", color = PravkaColors.Ink2)
                        if (today.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Pill(
                                "сегодня +${today.sumOf { it.letters }} букв · ${wordsWord(today.size)}",
                                bg = PravkaColors.GoodSoft, fg = PravkaColors.GoodText,
                            )
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = metric == Metric.SEC_PER_LETTER, onClick = { vm.setMetric(Metric.SEC_PER_LETTER) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("секунд на букву") }
                        SegmentedButton(
                            selected = metric == Metric.LETTERS_PER_MIN, onClick = { vm.setMetric(Metric.LETTERS_PER_MIN) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("букв в минуту") }
                    }
                }
            }

            item { LangCard(Lang.EN, en, attempts.filter { it.lang == Lang.EN.code }, metric) }
            item { LangCard(Lang.RU, ru, attempts.filter { it.lang == Lang.RU.code }, metric) }

            item {
                SecondaryButton("Добавить результат с бумаги", onClick = { showPaper = true }, modifier = Modifier.fillMaxWidth())
            }

            item { SectionTitle("История") }
            val history = attempts.sortedByDescending { it.ts }.take(40)
            if (history.isEmpty()) item { EmptyHint("Пока пусто") }
            items(history, key = { it.id }) { a ->
                HistoryRow(a, metric, onDelete = { deleting = a })
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showPaper) {
        PaperDialog(
            onAdd = { lang, word, letters, ms -> vm.addPaperAttempt(lang, word, letters, ms); showPaper = false },
            onDismiss = { showPaper = false },
        )
    }
    deleting?.let { a ->
        ConfirmDialog(
            title = "Удалить результат?",
            text = "${a.word ?: lettersWord(a.letters)} · ${fmtTime(a.ms)} · ${fmtDateTime(a.ts)}",
            onConfirm = { vm.deleteAttempt(a.id) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun LangCard(lang: Lang, st: LangStats, attempts: List<Attempt>, metric: Metric) {
    PravkaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lang.title(), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            LangTag(lang)
            Spacer(Modifier.weight(1f))
            Text(wordsWord(st.n), color = PravkaColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (st.n == 0) {
            EmptyHint("Ещё ни одного слова на этом языке")
            return@PravkaCard
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("рекорд", st.best?.let { fmtRateValue(it.secPerLetter, metric) } ?: "—", metricUnit(metric), Modifier.weight(1f), highlight = true)
            Kpi("среднее", st.avg?.let { fmtRateValue(it, metric) } ?: "—", metricUnit(metric), Modifier.weight(1f))
            Kpi("последние 5", st.last5?.let { fmtRateValue(it, metric) } ?: "—", metricUnit(metric), Modifier.weight(1f))
        }
        if (st.avg != null && st.last5 != null && st.n >= 5) {
            val pct = ((1 - st.last5 / st.avg) * 100).roundToInt()
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    pct >= 3 -> "Последние 5 слов быстрее средней на $pct%. Прогресс есть!"
                    pct <= -3 -> "Последние 5 слов медленнее средней на ${abs(pct)}%. Бывает, это не страшно."
                    else -> "Последние 5 слов идут ровно на уровне средней."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (pct >= 3) PravkaColors.GoodText else PravkaColors.Ink2,
            )
        }
        Spacer(Modifier.height(10.dp))
        SpeedChart(attempts = attempts, metric = metric, color = lang.color())
    }
}

@Composable
private fun Kpi(label: String, value: String, unit: String, modifier: Modifier, highlight: Boolean = false) {
    Column(
        modifier
            .background(if (highlight) PravkaColors.GoldSoft else PravkaColors.Surface2, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(value, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.Ink))
        Text(unit, style = MaterialTheme.typography.labelSmall, color = PravkaColors.Ink2)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = PravkaColors.Muted)
    }
}

@Composable
private fun HistoryRow(a: Attempt, metric: Metric, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(PravkaColors.Surface, MaterialTheme.shapes.medium).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LangTag(a.langEnum)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.word ?: lettersWord(a.letters), style = MaterialTheme.typography.titleSmall)
            Text(
                fmtDateTime(a.ts) + (if (a.word != null) " · ${lettersWord(a.letters)}" else "") +
                    (if (a.source == Attempt.SOURCE_PAPER) " · с бумаги" else ""),
                style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtTime(a.ms), style = MaterialTheme.typography.titleSmall)
            Text("${fmtRateValue(a.secPerLetter, metric)} ${metricUnit(metric)}", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = "Удалить", tint = PravkaColors.Muted) }
    }
}
