@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.domain.GrammarSetContent
import ru.tsakunov.pravka.domain.Overview
import ru.tsakunov.pravka.domain.OverviewCalc
import ru.tsakunov.pravka.domain.Period
import ru.tsakunov.pravka.domain.StudyKind
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtDuration
import ru.tsakunov.pravka.ui.fmtNum
import ru.tsakunov.pravka.ui.plural
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import kotlin.math.roundToInt

/** Общая статистика по всем разделам за неделю, месяц или всё время. */
@Composable
fun StatsScreen(vm: AppViewModel, onOpenProgress: () -> Unit, onIntake: () -> Unit, onTab: (Tab) -> Unit) {
    val attempts by vm.attempts.collectAsStateWithLifecycle()
    val quizRuns by vm.quizRuns.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val grammarProgress by vm.grammarProgress.collectAsStateWithLifecycle()
    val grammarSets by vm.grammarSets.collectAsStateWithLifecycle()
    val checks by vm.homeworkChecks.collectAsStateWithLifecycle()
    val readingRuns by vm.readingRuns.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(Period.WEEK) }

    val levelsTotal = remember(grammarSets) { grammarSets.sumOf { runCatching { GrammarSetContent.fromJson(it.contentJson).rules.size }.getOrDefault(0) } }
    val chartDays = when (period) { Period.WEEK -> 7; Period.MONTH -> 30; Period.ALL -> 30 }
    val current = remember(period, attempts, quizRuns, activity, grammarProgress, levelsTotal, checks, readingRuns) {
        OverviewCalc.compute(OverviewCalc.currentRange(period), attempts, quizRuns, activity, grammarProgress, levelsTotal, checks, readingRuns, chartDays)
    }
    val previous = remember(period, attempts, quizRuns, activity, grammarProgress, levelsTotal, checks, readingRuns) {
        OverviewCalc.previousRange(period)?.let { OverviewCalc.compute(it, attempts, quizRuns, activity, grammarProgress, levelsTotal, checks, readingRuns, 1) }
    }
    val streak = remember(attempts, quizRuns, activity, readingRuns, checks) {
        OverviewCalc.streakDays(OverviewCalc.activeDays(attempts, quizRuns, activity, readingRuns, checks))
    }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                title = { Text("Статистика", fontWeight = FontWeight.Bold) },
                actions = { IntakeIcon(onIntake) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
        bottomBar = { PravkaBottomBar(Tab.STATS, onTab) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Period.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = period == p,
                            onClick = { period = p },
                            shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = PravkaColors.Ink, activeContentColor = Color.White, inactiveContainerColor = PravkaColors.Surface),
                        ) { Text(p.label) }
                    }
                }
            }
            item { TimeCard(current, previous, streak, period) }
            item { WritingCard(current, previous, onOpenProgress) }
            item { WordsCard(current, previous, onOpen = { onTab(Tab.WORDS) }) }
            item { GrammarCard(current, previous, onOpen = { onTab(Tab.GRAMMAR) }) }
            item { HomeworkCard(current, previous, onOpen = { onTab(Tab.HOMEWORK) }) }
            item { ReadingCard(current, previous, onOpen = { onTab(Tab.TEXT) }) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun TimeCard(o: Overview, prev: Overview?, streak: Int, period: Period) {
    PravkaCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (o.time.totalMs == 0L) "0 мин" else fmtDuration(o.time.totalMs),
                style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.Ink, letterSpacing = (-1).sp),
            )
            Text(
                when (period) { Period.WEEK -> "занимался за неделю"; Period.MONTH -> "занимался за месяц"; Period.ALL -> "занимался за всё время" },
                color = PravkaColors.Ink2,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(plural(o.time.activeDays, "день", "дня", "дней") + " с занятиями", PravkaColors.Surface2, PravkaColors.Ink2)
                if (streak >= 2) Pill("серия $streak дней подряд", PravkaColors.GoldSoft, PravkaColors.GoldText)
            }
            DeltaLine(o.time.totalMs.toDouble(), prev?.time?.totalMs?.toDouble(), period, higherIsBetter = true, what = "времени")
        }
        Spacer(Modifier.height(12.dp))
        MinutesChart(o.time.byDay)
        if (o.time.totalMs > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                StudyKind.entries.filter { (o.time.byKind[it] ?: 0L) > 0 }
                    .joinToString(" · ") { "${it.label} ${fmtDuration(o.time.byKind[it] ?: 0L)}" },
                style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
            )
        }
    }
}

@Composable
private fun WritingCard(o: Overview, prev: Overview?, onOpen: () -> Unit) {
    val w = o.writing
    SectionCard("Гармошка", StudyKind.WRITING.color(), onOpen, more = "Подробнее") {
        if (w.words == 0) { EmptyLine("Слов пока не писали"); return@SectionCard }
        StatRow(
            Stat(w.letters.toString(), "букв"),
            Stat(w.words.toString(), "слов"),
            Stat(w.enAvg?.let { fmtNum(it) } ?: "–", "с/букву EN"),
            Stat(w.ruAvg?.let { fmtNum(it) } ?: "–", "с/букву RU"),
        )
        val bests = listOfNotNull(
            w.enBest?.let { "EN лучшее: ${it.word ?: ""} ${fmtNum(it.secPerLetter)} с/букву" },
            w.ruBest?.let { "RU лучшее: ${it.word ?: ""} ${fmtNum(it.secPerLetter)} с/букву" },
        )
        if (bests.isNotEmpty()) Text(bests.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2)
        DeltaLine(w.letters.toDouble(), prev?.writing?.letters?.toDouble(), null, higherIsBetter = true, what = "букв")
        prev?.writing?.enAvg?.let { pa -> w.enAvg?.let { ca -> DeltaLine(ca, pa, null, higherIsBetter = false, what = "секунд на букву по-английски") } }
    }
}

@Composable
private fun WordsCard(o: Overview, prev: Overview?, onOpen: () -> Unit) {
    val w = o.words
    SectionCard("Слова", StudyKind.WORDS.color(), onOpen) {
        if (w.empty) { EmptyLine("Карточки и контроша ещё не открывались"); return@SectionCard }
        StatRow(
            Stat(w.quizWordsSaid.toString(), "слов сказал верно"),
            Stat(w.quizRuns.toString(), "контрош"),
            Stat(w.quizFirstTryPct?.let { "$it%" } ?: "–", "с первой попытки"),
        )
        if (w.teachRounds > 0) {
            Text(
                "Училка: ${plural(w.teachRounds, "круг", "круга", "кругов")}, ${plural(w.teachWords, "слово", "слова", "слов")}, сразу знал ${w.teachKnownPct ?: 0}%",
                style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2,
            )
        }
        if (w.learnCards > 0) {
            Text("Обучалка: ${plural(w.learnCards, "карточка", "карточки", "карточек")} просмотрено", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2)
        }
        DeltaLine(w.quizWordsSaid.toDouble(), prev?.words?.quizWordsSaid?.toDouble(), null, higherIsBetter = true, what = "слов на контроше")
    }
}

@Composable
private fun GrammarCard(o: Overview, prev: Overview?, onOpen: () -> Unit) {
    val g = o.grammar
    SectionCard("Грамматика", StudyKind.GRAMMAR.color(), onOpen) {
        if (g.answers == 0 && g.levelsTotal == 0) { EmptyLine("Тем по грамматике ещё нет"); return@SectionCard }
        StatRow(
            Stat(g.answers.toString(), "ответов"),
            Stat(g.correctPct?.let { "$it%" } ?: "–", "верно"),
            Stat("${g.levelsPassed}/${g.levelsTotal}", "уровней пройдено"),
            Stat(g.bestStreak.toString(), "лучшая серия"),
        )
        if (g.answers == 0) Text("За этот период тренажёр не открывали", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted)
        prev?.grammar?.correctPct?.let { pp -> g.correctPct?.let { cp ->
            val d = cp - pp
            if (d != 0) Text(
                if (d > 0) "Точность выше на $d п.п., чем в прошлый период" else "Точность ниже на ${-d} п.п., чем в прошлый период",
                style = MaterialTheme.typography.bodySmall, color = if (d > 0) PravkaColors.GoodText else PravkaColors.Danger,
            )
        } }
    }
}

@Composable
private fun HomeworkCard(o: Overview, prev: Overview?, onOpen: () -> Unit) {
    val h = o.homework
    SectionCard("Домашка", PravkaColors.Ru, onOpen) {
        if (h.homeworks == 0) { EmptyLine("Проверенных домашек за период нет"); return@SectionCard }
        StatRow(
            Stat(h.homeworks.toString(), "домашек"),
            Stat(h.firstWrong.toString(), "ошибок с первого раза"),
            Stat(h.errorPct?.let { "$it%" } ?: "–", "пунктов с ошибкой"),
        )
        Text(
            buildString {
                append("Без ошибок сразу: ").append(h.perfectFirst).append(" из ").append(h.homeworks)
                if (h.fixedLater > 0) append(" · исправлено до конца: ").append(h.fixedLater)
                if (h.checks > h.homeworks) append(" · проверок всего: ").append(h.checks)
            },
            style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2,
        )
        prev?.homework?.errorPct?.let { pp -> h.errorPct?.let { cp ->
            val d = cp - pp
            if (d != 0) Text(
                if (d < 0) "Ошибок меньше на ${-d} п.п., чем в прошлый период" else "Ошибок больше на $d п.п., чем в прошлый период",
                style = MaterialTheme.typography.bodySmall, color = if (d < 0) PravkaColors.GoodText else PravkaColors.Danger,
            )
        } }
    }
}

@Composable
private fun ReadingCard(o: Overview, prev: Overview?, onOpen: () -> Unit) {
    val r = o.reading
    SectionCard("Текст", StudyKind.READING.color(), onOpen) {
        if (r.runs == 0) { EmptyLine("Чтений за период нет"); return@SectionCard }
        StatRow(
            Stat(r.runs.toString(), "чтений"),
            Stat(r.bestWpm?.roundToInt()?.toString() ?: "–", "лучшее сл/мин"),
            Stat(r.avgWpm?.roundToInt()?.toString() ?: "–", "среднее сл/мин"),
            Stat(r.words.toString(), "слов прочитано"),
        )
        if (r.micRuns > 0) {
            Text(
                buildString {
                    append("С микрофоном: ").append(plural(r.micRuns, "чтение", "чтения", "чтений"))
                    r.readOkPct?.let { append(" · чисто прочитано ").append(it).append("% предложений") }
                    r.transOkPct?.let { append(" · перевод верен в ").append(it).append("%") }
                },
                style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2,
            )
        }
        prev?.reading?.avgWpm?.let { pa -> r.avgWpm?.let { ca -> DeltaLine(ca, pa, null, higherIsBetter = true, what = "скорости чтения") } }
        prev?.reading?.transOkPct?.let { pp -> r.transOkPct?.let { cp ->
            val d = cp - pp
            if (d != 0) Text(
                if (d > 0) "Перевод точнее на $d п.п., чем в прошлый период" else "Перевод слабее на ${-d} п.п., чем в прошлый период",
                style = MaterialTheme.typography.bodySmall, color = if (d > 0) PravkaColors.GoodText else PravkaColors.Danger,
            )
        } }
    }
}

// ---- Кирпичики ----

private data class Stat(val value: String, val label: String)

@Composable
private fun SectionCard(title: String, accent: Color, onOpen: () -> Unit, more: String = "Открыть", content: @Composable ColumnScope.() -> Unit) {
    PravkaCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = accent, modifier = Modifier.weight(1f))
            Text(more, style = MaterialTheme.typography.labelMedium, color = PravkaColors.Muted)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = more, tint = PravkaColors.Muted)
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun StatRow(vararg stats: Stat) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        stats.forEach { s ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.value, style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.Ink), maxLines = 1)
                Text(s.label, style = MaterialTheme.typography.labelSmall, color = PravkaColors.Muted, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted)
}

/** Строка сравнения с прошлым периодом: «на 12% больше времени, чем неделей раньше». */
@Composable
private fun DeltaLine(current: Double, previous: Double?, period: Period?, higherIsBetter: Boolean, what: String) {
    if (previous == null) return
    val pct = OverviewCalc.deltaPct(current, previous) ?: return
    if (pct == 0) return
    val good = (pct > 0) == higherIsBetter
    val suffix = when (period) { Period.WEEK -> "неделей раньше"; Period.MONTH -> "месяцем раньше"; else -> "в прошлый период" }
    Text(
        if (pct > 0) "На $pct% больше $what, чем $suffix" else "На ${-pct}% меньше $what, чем $suffix",
        style = MaterialTheme.typography.bodySmall, color = if (good) PravkaColors.GoodText else PravkaColors.Danger,
    )
}
