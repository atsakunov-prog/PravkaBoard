@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import android.os.SystemClock
import ru.tsakunov.pravka.data.ActivityLog
import ru.tsakunov.pravka.data.GrammarSet
import ru.tsakunov.pravka.domain.Drill
import ru.tsakunov.pravka.domain.GrammarSetContent
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtDate
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.vm.GrammarState

/** Вкладка «Грамматика»: сфотографировать правило, список тем с прогрессом. */
@Composable
fun GrammarListScreen(vm: AppViewModel, onOpen: (String) -> Unit, onIntake: () -> Unit, onTab: (Tab) -> Unit) {
    val sets by vm.grammarSets.collectAsStateWithLifecycle()
    val progress by vm.grammarProgress.collectAsStateWithLifecycle()
    val state by vm.grammarState.collectAsStateWithLifecycle()
    val picker = rememberPhotoPicker(onError = { vm.showToast(it) })
    var deleteTarget by remember { mutableStateOf<GrammarSet?>(null) }

    LaunchedEffect(state) {
        val s = state
        if (s is GrammarState.Done) { picker.clear(); vm.grammarHandled(); onOpen(s.setId) }
    }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                title = { Text("Грамматика", fontWeight = FontWeight.Bold) },
                actions = { IntakeIcon(onIntake) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
        bottomBar = { PravkaBottomBar(Tab.GRAMMAR, onTab) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PravkaCard {
                    Text("Новая тема", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Сфотографируй страницу с правилом. Opus разложит её на уровни: слово на экране, Боря говорит форму, папа отмечает. Пять верных подряд открывают следующий уровень.",
                        style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2,
                    )
                    Spacer(Modifier.height(10.dp))
                    PhotoPickerControls(
                        picker = picker,
                        cameraLabel = "Сфотографировать правило",
                        submitLabel = "Сделать тренажёр (Opus)",
                        submitting = state is GrammarState.Running,
                        onSubmit = { vm.buildGrammar(picker.pending) },
                    )
                }
            }
            if (sets.isNotEmpty()) item { SectionTitle("Темы") }
            items(sets, key = { it.id }) { set ->
                val content = remember(set.id) { runCatching { GrammarSetContent.fromJson(set.contentJson) }.getOrNull() }
                val passed = progress.count { it.setId == set.id && it.passed }
                val levels = content?.rules?.size ?: 0
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(set.id) },
                    shape = MaterialTheme.shapes.large,
                    color = PravkaColors.Surface,
                    border = BorderStroke(1.dp, PravkaColors.Border),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(set.title, style = MaterialTheme.typography.titleMedium)
                            Text("$levels уровней · ${fmtDate(set.createdAt)}", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted)
                        }
                        Pill(
                            if (levels > 0 && passed == levels) "все уровни" else "$passed / $levels",
                            bg = if (levels > 0 && passed == levels) PravkaColors.GoodSoft else PravkaColors.Surface2,
                            fg = if (levels > 0 && passed == levels) PravkaColors.GoodText else PravkaColors.Ink2,
                        )
                        IconButton(onClick = { deleteTarget = set }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = PravkaColors.Muted) }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    deleteTarget?.let { t ->
        ConfirmDialog(title = "Удалить тему?", text = "«${t.title}» и прогресс по ней будут удалены.", onConfirm = { vm.deleteGrammarSet(t.id) }, onDismiss = { deleteTarget = null })
    }
    when (val s = state) {
        is GrammarState.Running -> WorkingDialog("Opus собирает тренажёр", s.photos)
        is GrammarState.Error -> AlertDialog(
            onDismissRequest = { vm.grammarHandled() },
            title = { Text("Не получилось") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.grammarHandled() }) { Text("Понятно") } },
        )
        else -> Unit
    }
}

/** Уровни одной темы. Открыт первый и все, что следуют за пройденными. */
@Composable
fun GrammarSetScreen(vm: AppViewModel, setId: String, onBack: () -> Unit, onDrill: (Int) -> Unit) {
    val set by vm.observeGrammarSet(setId).collectAsStateWithLifecycle(initialValue = null)
    val progress by vm.observeGrammarProgress(setId).collectAsStateWithLifecycle(initialValue = emptyList())
    val content = remember(set?.id) { set?.let { runCatching { GrammarSetContent.fromJson(it.contentJson) }.getOrNull() } }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                navigationIcon = { BackIcon(onBack) },
                title = { Text(set?.title ?: "", fontWeight = FontWeight.Bold, maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val rules = content?.rules ?: emptyList()
            if (set == null) return@LazyColumn // ещё грузится
            if (rules.isEmpty()) { item { EmptyHint("В теме нет уровней") }; return@LazyColumn }
            itemsIndexed(rules) { index, rule ->
                val p = progress.firstOrNull { it.ruleIndex == index }
                val unlocked = index == 0 || progress.any { it.ruleIndex == index - 1 && it.passed }
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = unlocked) { onDrill(index) },
                    shape = MaterialTheme.shapes.large,
                    color = if (unlocked) PravkaColors.Surface else PravkaColors.Surface2,
                    border = BorderStroke(1.dp, if (p?.passed == true) PravkaColors.Good.copy(alpha = 0.5f) else PravkaColors.Border),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            Modifier.size(40.dp).background(
                                when { p?.passed == true -> PravkaColors.GoodSoft; unlocked -> PravkaColors.EnSoft; else -> PravkaColors.Grid },
                                CircleShape,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!unlocked) Icon(Icons.Filled.Lock, contentDescription = null, tint = PravkaColors.Muted, modifier = Modifier.size(18.dp))
                            else Text("${index + 1}", fontWeight = FontWeight.Bold, color = if (p?.passed == true) PravkaColors.GoodText else PravkaColors.EnText)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(rule.name, style = MaterialTheme.typography.titleMedium, color = if (unlocked) PravkaColors.Ink else PravkaColors.Muted)
                            Text(rule.explanation, style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2, maxLines = 3)
                            if (p != null) {
                                Text(
                                    (if (p.passed) "пройден · " else "") + "лучшая серия ${p.bestStreak} · верно ${p.correct} из ${p.total}",
                                    style = MaterialTheme.typography.labelSmall, color = if (p.passed) PravkaColors.GoodText else PravkaColors.Muted,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** Тренажёр уровня: слово на экране, Боря отвечает вслух, папа жмёт «верно» или «не верно». */
@Composable
fun GrammarDrillScreen(vm: AppViewModel, setId: String, ruleIndex: Int, onBack: () -> Unit) {
    val set by vm.observeGrammarSet(setId).collectAsStateWithLifecycle(initialValue = null)
    val progress by vm.observeGrammarProgress(setId).collectAsStateWithLifecycle(initialValue = emptyList())
    val content = remember(set?.id) { set?.let { runCatching { GrammarSetContent.fromJson(it.contentJson) }.getOrNull() } }
    val rule = content?.rules?.getOrNull(ruleIndex)
    val speaker = rememberSpeaker()
    val scope = rememberCoroutineScope()

    var order by remember(rule) { mutableStateOf(rule?.drills?.indices?.shuffled() ?: emptyList()) }
    var pos by remember(rule) { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var streak by remember { mutableIntStateOf(0) }
    var justPassed by remember { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }
    val p = progress.firstOrNull { it.ruleIndex == ruleIndex }

    // Журнал занятий: ответов и верных за этот заход, пишется при уходе с экрана.
    val startedAt = remember(setId, ruleIndex) { SystemClock.elapsedRealtime() }
    var answered by remember(setId, ruleIndex) { mutableIntStateOf(0) }
    var answeredCorrect by remember(setId, ruleIndex) { mutableIntStateOf(0) }
    DisposableEffect(setId, ruleIndex) {
        onDispose {
            if (answered > 0) vm.logActivity(ActivityLog.KIND_GRAMMAR, setId, SystemClock.elapsedRealtime() - startedAt, answered, answeredCorrect)
        }
    }

    fun next() {
        revealed = false
        if (pos + 1 < order.size) pos++ else { order = order.shuffled(); pos = 0 }
    }

    fun answer(correct: Boolean) {
        if (!revealed) return // второе нажатие, пока пишется ответ
        revealed = false
        answered++
        if (correct) answeredCorrect++
        val wasPassed = p?.passed == true
        scope.launch {
            val np = vm.recordGrammarAnswer(setId, ruleIndex, correct, streak)
            streak = if (correct) streak + 1 else 0
            if (!wasPassed && np.passed) { justPassed = true; confetti++ }
            next()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = PravkaColors.Page,
            topBar = {
                TopAppBar(
                    navigationIcon = { BackIcon(onBack) },
                    title = { Text(rule?.name ?: "", fontWeight = FontWeight.Bold, maxLines = 1) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (rule == null) { if (set != null) EmptyHint("Уровень не найден"); return@Column }
                Text(rule.explanation, style = MaterialTheme.typography.bodyMedium, color = PravkaColors.Ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                StreakDots(streak, GrammarSetContent.STREAK_TO_PASS, passed = p?.passed == true)
                Spacer(Modifier.weight(1f))
                if (justPassed) {
                    Text("Уровень пройден!", style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.GoodText), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("Следующий уровень открыт. Можно продолжить тренировку здесь или вернуться к списку.", color = PravkaColors.Ink2, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    BigButton("К уровням", onClick = onBack, container = PravkaColors.Good)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Продолжить", onClick = { justPassed = false }, modifier = Modifier.fillMaxWidth())
                } else {
                    val drill: Drill? = order.getOrNull(pos)?.let { rule.drills.getOrNull(it) }
                    if (drill == null) { EmptyHint("В уровне нет карточек"); return@Column }
                    DrillCard(drill, revealed, onSpeak = { speaker.speak(if (revealed) drill.answer else drill.prompt) })
                    Spacer(Modifier.height(16.dp))
                    if (!revealed) {
                        Text("Боря говорит ответ вслух, потом открой и отметь", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        BigButton("Показать ответ", onClick = { revealed = true; speaker.speak(drill.answer) })
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BigButton("Не верно", onClick = { answer(false) }, container = PravkaColors.Danger, modifier = Modifier.weight(1f))
                            BigButton("Верно", onClick = { answer(true) }, container = PravkaColors.Good, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (p != null) Text("Всего верно ${p.correct} из ${p.total} · лучшая серия ${p.bestStreak}", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted)
                Spacer(Modifier.height(12.dp))
            }
        }
        ConfettiOverlay(trigger = confetti, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun StreakDots(streak: Int, need: Int, passed: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(need) { i ->
            Box(Modifier.size(14.dp).background(if (i < streak) PravkaColors.Good else PravkaColors.Grid, CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(if (passed) "уровень уже пройден" else "серия $streak из $need", style = MaterialTheme.typography.labelMedium, color = if (passed) PravkaColors.GoodText else PravkaColors.Ink2)
    }
}

@Composable
private fun DrillCard(drill: Drill, revealed: Boolean, onSpeak: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = PravkaColors.Surface,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, PravkaColors.Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(drill.prompt, style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.Ink), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            IconButton(onClick = onSpeak, modifier = Modifier.size(48.dp).background(PravkaColors.EnSoft, CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Озвучить", tint = PravkaColors.EnText)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = PravkaColors.Grid)
            Spacer(Modifier.height(16.dp))
            if (revealed) {
                Text(drill.answer, style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.GoodText), textAlign = TextAlign.Center)
                if (drill.note.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(drill.note, style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2, textAlign = TextAlign.Center)
                }
            } else {
                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    Text("?", style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.Grid))
                }
            }
        }
    }
}
