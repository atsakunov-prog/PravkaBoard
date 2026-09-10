@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.tsakunov.pravka.data.ReadingRun
import ru.tsakunov.pravka.domain.countWords
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtDate
import ru.tsakunov.pravka.ui.fmtTime
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
    val picker = rememberPhotoPicker(onError = { vm.showToast(it) })
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
                        "Сфотографируй разворот книжки. Потом Боря читает вслух на время, а ты жмёшь «запинка», когда он спотыкается. Считаем слова в минуту.",
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
                        if (best != null) Pill("${best.wordsPerMinute.roundToInt()} сл/мин", PravkaColors.EnSoft, PravkaColors.EnText)
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

/** Короче этого чтение не бывает: такой СТОП считаем случайным и не сохраняем. */
private const val MIN_READING_MS = 3_000L

private sealed interface ReadPhase {
    data object Ready : ReadPhase
    data class Running(val startedAt: Long) : ReadPhase
    /** СТОП нажат, результат пишется в базу: кнопки спрятаны, второе нажатие ничего не делает. */
    data class Saving(val ms: Long) : ReadPhase
    data class Result(val run: ReadingRun, val prevBest: ReadingRun?, val isRecord: Boolean) : ReadPhase
}

/** Чтение на время: текст, СТАРТ, кнопка «запинка», СТОП, слова в минуту и сравнение с прошлым разом. */
@Composable
fun ReadingScreen(vm: AppViewModel, textId: String, onBack: () -> Unit) {
    val text by vm.observeReadingText(textId).collectAsStateWithLifecycle(initialValue = null)
    val story by vm.observeStoryById(textId).collectAsStateWithLifecycle(initialValue = null)
    val runs by vm.readingRuns.collectAsStateWithLifecycle()
    val speaker = rememberSpeaker()
    val scope = rememberCoroutineScope()

    val readable = text?.let { Readable(it.id, it.title, it.textEn, it.textRu, it.words, it.createdAt, false) }
        ?: story?.let { Readable(it.id, it.title, it.textEn, it.textRu, countWords(it.textEn), it.createdAt, true) }
    val own = remember(runs, textId) { runs.filter { it.textId == textId }.sortedBy { it.ts } }

    var phase by remember(textId) { mutableStateOf<ReadPhase>(ReadPhase.Ready) }
    var elapsed by remember(textId) { mutableLongStateOf(0L) }
    var stumbles by remember(textId) { mutableIntStateOf(0) }
    var showRu by rememberSaveable { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }

    val running = phase as? ReadPhase.Running
    LaunchedEffect(running) {
        if (running == null) return@LaunchedEffect
        while (true) { elapsed = SystemClock.elapsedRealtime() - running.startedAt; delay(100) }
    }
    BackHandler(enabled = phase is ReadPhase.Running) { phase = ReadPhase.Ready; elapsed = 0; stumbles = 0 }

    fun stop() {
        val r = phase as? ReadPhase.Running ?: return
        val t = readable ?: return
        val ms = SystemClock.elapsedRealtime() - r.startedAt
        if (ms < MIN_READING_MS) {
            // Случайный СТОП сразу после СТАРТа: не записываем, чтобы не испортить «лучшее».
            phase = ReadPhase.Ready; elapsed = 0; stumbles = 0
            vm.showToast("Слишком быстро, это не считается. Жми СТАРТ ещё раз")
            return
        }
        elapsed = ms
        phase = ReadPhase.Saving(ms)
        scope.launch {
            val prevBest = own.maxByOrNull { it.wordsPerMinute }
            val run = vm.saveReadingRun(textId, ms, stumbles, t.words)
            val isRecord = prevBest != null && run.wordsPerMinute > prevBest.wordsPerMinute
            phase = ReadPhase.Result(run, prevBest, isRecord)
            if (isRecord || (prevBest == null && own.isEmpty())) confetti++
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = PravkaColors.Page,
            topBar = {
                TopAppBar(
                    navigationIcon = { BackIcon { speaker.stop(); onBack() } },
                    title = { Text(readable?.title ?: "Текст", fontWeight = FontWeight.Bold, maxLines = 1) },
                    actions = {
                        if (phase !is ReadPhase.Running) {
                            IconButton(onClick = { readable?.let { speaker.speak(it.textEn, slow = true) } }) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Прочитать вслух", tint = PravkaColors.EnText)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
                )
            },
        ) { padding ->
            val t = readable
            // Пока Room не ответил, экран пустой; «не найден» показываем только для удалённого текста.
            if (t == null) { Box(Modifier.fillMaxSize().padding(padding)); return@Scaffold }
            Column(Modifier.fillMaxSize().padding(padding)) {
                // Текст занимает всё, что остаётся над панелью управления.
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        t.textEn,
                        style = TextStyle(fontSize = if (phase is ReadPhase.Running) 26.sp else 21.sp, lineHeight = if (phase is ReadPhase.Running) 40.sp else 32.sp, color = PravkaColors.Ink),
                    )
                    if (showRu && t.textRu.isNotBlank() && phase !is ReadPhase.Running) {
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = PravkaColors.Grid)
                        Spacer(Modifier.height(12.dp))
                        Text(t.textRu, style = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, color = PravkaColors.Ink2))
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Surface(color = PravkaColors.Surface, shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (val p = phase) {
                            ReadPhase.Ready -> {
                                val best = own.maxByOrNull { it.wordsPerMinute }
                                Text(
                                    "${plural(t.words, "слово", "слова", "слов")}" + (best?.let { " · лучшее ${it.wordsPerMinute.roundToInt()} сл/мин за ${fmtTime(it.durationMs)}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Перевод", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2)
                                    Spacer(Modifier.width(6.dp))
                                    Switch(checked = showRu, onCheckedChange = { showRu = it })
                                    Spacer(Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(6.dp))
                                BigButton("СТАРТ", onClick = { stumbles = 0; elapsed = 0; phase = ReadPhase.Running(SystemClock.elapsedRealtime()) }, container = PravkaColors.Good)
                            }
                            is ReadPhase.Running -> {
                                Text(
                                    fmtTime(elapsed),
                                    style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum", color = PravkaColors.Danger),
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BigButton(
                                        if (stumbles == 0) "Запинка" else "Запинка · $stumbles",
                                        onClick = { stumbles++ }, container = PravkaColors.Gold, modifier = Modifier.weight(1f),
                                    )
                                    BigButton("СТОП", onClick = { stop() }, container = PravkaColors.Danger, modifier = Modifier.weight(1f))
                                }
                            }
                            is ReadPhase.Saving -> {
                                Text(
                                    fmtTime(p.ms),
                                    style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum", color = PravkaColors.Ink),
                                )
                                Spacer(Modifier.height(8.dp))
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = PravkaColors.Good)
                            }
                            is ReadPhase.Result -> {
                                Text(
                                    "${p.run.wordsPerMinute.roundToInt()} слов в минуту",
                                    style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = if (p.isRecord) PravkaColors.GoodText else PravkaColors.Ink),
                                )
                                Text(
                                    "${fmtTime(p.run.durationMs)} · ${plural(p.run.stumbles, "запинка", "запинки", "запинок")}" +
                                        (p.prevBest?.takeIf { it.wordsPerMinute > 0 }?.let { pb ->
                                            val pct = ((p.run.wordsPerMinute / pb.wordsPerMinute - 1) * 100).roundToInt()
                                            if (pct > 0) " · быстрее лучшего на $pct%" else if (pct < 0) " · лучшее было ${pb.wordsPerMinute.roundToInt()}" else ""
                                        } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium, color = PravkaColors.Ink2, textAlign = TextAlign.Center,
                                )
                                if (p.isRecord) Text("Новый рекорд чтения!", color = PravkaColors.GoldText, fontWeight = FontWeight.Bold)
                                if (p.run.stumbles == 0) Text("Без единой запинки", color = PravkaColors.GoodText, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SecondaryButton("Отменить", onClick = { vm.deleteReadingRun(p.run.id); phase = ReadPhase.Ready }, modifier = Modifier.weight(1f))
                                    BigButton("Ещё раз", onClick = { phase = ReadPhase.Ready }, modifier = Modifier.weight(1f))
                                }
                                if (own.size >= 2) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Чтения: " + own.takeLast(5).joinToString(" · ") { "${it.wordsPerMinute.roundToInt()}" } + " сл/мин",
                                        style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        ConfettiOverlay(trigger = confetti, modifier = Modifier.fillMaxSize())
    }
}
