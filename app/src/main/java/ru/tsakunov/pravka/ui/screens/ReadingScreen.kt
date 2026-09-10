@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.tsakunov.pravka.api.SentenceDraft
import ru.tsakunov.pravka.data.ReadingRun
import ru.tsakunov.pravka.domain.ReadingDetail
import ru.tsakunov.pravka.domain.SentenceResult
import ru.tsakunov.pravka.domain.Sentences
import ru.tsakunov.pravka.domain.countWords
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtTime
import ru.tsakunov.pravka.ui.plural
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import kotlin.math.roundToInt

/** Короче этого чтение не бывает: такой СТОП считаем случайным и не сохраняем. */
private const val MIN_READING_MS = 3_000L

/** Шаг микрофонного чтения: сначала предложение по-английски, потом его перевод. */
private enum class Step { READ, TRANSLATE }

private sealed interface ReadPhase {
    data object Ready : ReadPhase
    /** Секундомер без микрофона: папа жмёт «запинка» и СТОП. */
    data class Timer(val startedAt: Long) : ReadPhase
    /** СТОП нажат, результат пишется в базу. */
    data class Saving(val ms: Long) : ReadPhase
    /** Микрофон слушает предложение idx на шаге step; attempt растёт при повторе, hint — почему остановились. */
    data class Mic(val idx: Int, val step: Step, val attempt: Int = 0, val hint: String? = null, val error: Boolean = false) : ReadPhase
    /** Все предложения прочитаны, ждём вердикт модели. */
    data object Judging : ReadPhase
    data class Result(val run: ReadingRun, val prevBest: ReadingRun?, val prevMic: ReadingRun?, val isRecord: Boolean, val detail: ReadingDetail?, val warning: String?) : ReadPhase
}

/**
 * Чтение текста. Основной режим: Боря читает предложение с микрофоном по-английски, потом говорит перевод,
 * приложение слушает оба шага, в конце Opus разбирает чтение и перевод, а результат сравнивается с прошлым разом.
 * Запасной режим: секундомер с кнопкой «запинка».
 */
@Composable
fun ReadingScreen(vm: AppViewModel, textId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val text by vm.observeReadingText(textId).collectAsStateWithLifecycle(initialValue = null)
    val story by vm.observeStoryById(textId).collectAsStateWithLifecycle(initialValue = null)
    val runs by vm.readingRuns.collectAsStateWithLifecycle()
    val speaker = rememberSpeaker()
    val speech = remember { SpeechInput(context) }
    val scope = rememberCoroutineScope()

    val readable = text?.let { Readable(it.id, it.title, it.textEn, it.textRu, it.words, it.createdAt, false) }
        ?: story?.let { Readable(it.id, it.title, it.textEn, it.textRu, countWords(it.textEn), it.createdAt, true) }
    val sentences = remember(readable?.textEn) { readable?.let { Sentences.split(it.textEn) } ?: emptyList() }
    val own = remember(runs, textId) { runs.filter { it.textId == textId }.sortedBy { it.ts } }

    var phase by remember(textId) { mutableStateOf<ReadPhase>(ReadPhase.Ready) }
    var elapsed by remember(textId) { mutableLongStateOf(0L) }
    var stumbles by remember(textId) { mutableIntStateOf(0) }
    var showRu by rememberSaveable { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }

    // Черновики микрофонного чтения: по одному на предложение.
    val drafts = remember(textId) { mutableStateListOf<SentenceDraft>() }
    var sessionStart by remember(textId) { mutableLongStateOf(0L) }
    var partial by remember { mutableStateOf("") }
    var speechBegan by remember { mutableLongStateOf(0L) }
    var speechEnded by remember { mutableLongStateOf(0L) }
    var listenStart by remember { mutableLongStateOf(0L) }

    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    fun startMic() {
        if (sentences.isEmpty()) { vm.showToast("В тексте нет предложений"); return }
        drafts.clear()
        repeat(sentences.size) { i -> drafts.add(SentenceDraft(sentences[i], "", "", 0L, enSkipped = false, ruSkipped = false)) }
        sessionStart = SystemClock.elapsedRealtime()
        partial = ""
        phase = ReadPhase.Mic(0, Step.READ)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) startMic() else vm.showToast("Без микрофона можно читать с секундомером")
    }
    DisposableEffect(Unit) { onDispose { speech.release() } }

    // Секундомер.
    val timer = phase as? ReadPhase.Timer
    LaunchedEffect(timer) {
        if (timer == null) return@LaunchedEffect
        while (true) { elapsed = SystemClock.elapsedRealtime() - timer.startedAt; delay(100) }
    }
    val mic = phase as? ReadPhase.Mic
    LaunchedEffect(mic != null) {
        if (mic == null) return@LaunchedEffect
        while (true) { elapsed = SystemClock.elapsedRealtime() - sessionStart; delay(500) }
    }
    BackHandler(enabled = phase is ReadPhase.Timer || phase is ReadPhase.Mic) {
        speech.stop(); phase = ReadPhase.Ready; elapsed = 0; stumbles = 0
    }

    fun finishMic(upTo: Int) {
        speech.stop()
        val t = readable ?: return
        val total = SystemClock.elapsedRealtime() - sessionStart
        val done = drafts.take(upTo).toList()
        if (done.isEmpty() || done.all { it.enSkipped }) { phase = ReadPhase.Ready; return }
        phase = ReadPhase.Judging
        val prevBest = own.maxByOrNull { it.wordsPerMinute }
        val prevMic = own.lastOrNull { it.isMic }
        scope.launch {
            // Разбор и запись идут в области ViewModel: уход с экрана их не оборвёт.
            val (run, detail, warning) = vm.finishMicReading(textId, t.textRu, done, total).await()
            val isRecord = prevBest != null && run.wordsPerMinute > prevBest.wordsPerMinute
            val betterTranslation = prevMic?.transOk != null && run.transOk != null && run.transOk > prevMic.transOk
            phase = ReadPhase.Result(run, prevBest, prevMic, isRecord, detail, warning)
            if (isRecord || betterTranslation || own.isEmpty()) confetti++
        }
    }

    fun advance(m: ReadPhase.Mic) {
        phase = if (m.step == Step.READ) ReadPhase.Mic(m.idx, Step.TRANSLATE)
        else if (m.idx + 1 < sentences.size) ReadPhase.Mic(m.idx + 1, Step.READ)
        else { finishMic(sentences.size); return }
    }

    // Слушаем текущий шаг. Ключи: предложение, шаг, номер попытки; hint без микрофона.
    LaunchedEffect(mic?.idx, mic?.step, mic?.attempt, mic?.hint) {
        val m = mic ?: return@LaunchedEffect
        if (m.hint != null) return@LaunchedEffect
        if (!speech.available) { phase = m.copy(hint = "На телефоне нет распознавания речи Google", error = true); return@LaunchedEffect }
        if (m.attempt > 0) delay(500)
        speaker.stop()
        partial = ""
        speechBegan = 0L
        speechEnded = 0L
        listenStart = SystemClock.elapsedRealtime()
        speech.start(
            language = if (m.step == Step.READ) "en-US" else "ru-RU",
            silenceMs = if (m.step == Step.READ) 2500L else 1800L,
            onPartial = { partial = it },
            onBegin = { speechBegan = SystemClock.elapsedRealtime() },
            onEnd = { speechEnded = SystemClock.elapsedRealtime() },
            onResult = { results ->
                // Результат принадлежит именно этому запуску: после «Ещё раз» или паузы фаза уже другая.
                if (phase != m) return@start
                val best = results.firstOrNull()?.trim() ?: ""
                val now = SystemClock.elapsedRealtime()
                // Время чтения: от начала речи до её конца по данным движка, без ожидания тишины и распознавания.
                val begin = if (speechBegan > 0) speechBegan else listenStart
                val end = if (speechEnded > begin) speechEnded else now
                val spoke = end - begin
                val d = drafts[m.idx]
                drafts[m.idx] = if (m.step == Step.READ) d.copy(heardEn = best, readMs = spoke.coerceAtLeast(500)) else d.copy(heardRu = best)
                partial = ""
                advance(m)
            },
            onError = { code ->
                if (phase != m) return@start
                if (SpeechInput.isRetryable(code) && m.attempt < 2) phase = m.copy(attempt = m.attempt + 1)
                else phase = m.copy(hint = SpeechInput.describeError(code), error = true)
            },
        )
    }

    fun stopTimer() {
        val r = phase as? ReadPhase.Timer ?: return
        val t = readable ?: return
        val ms = SystemClock.elapsedRealtime() - r.startedAt
        if (ms < MIN_READING_MS) {
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
            phase = ReadPhase.Result(run, prevBest, null, isRecord, null, null)
            if (isRecord || own.isEmpty()) confetti++
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = PravkaColors.Page,
            topBar = {
                TopAppBar(
                    navigationIcon = { BackIcon { speech.stop(); speaker.stop(); onBack() } },
                    title = { Text(readable?.title ?: "Текст", fontWeight = FontWeight.Bold, maxLines = 1) },
                    actions = {
                        if (phase is ReadPhase.Ready || phase is ReadPhase.Result) {
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
            // Пока Room не ответил, экран пустой.
            if (t == null) { Box(Modifier.fillMaxSize().padding(padding)); return@Scaffold }
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                    when (val p = phase) {
                        is ReadPhase.Result -> if (p.detail != null) ResultDetails(p.detail) else PlainText(t, showRu, large = false)
                        is ReadPhase.Mic -> SentenceText(sentences, p.idx, p.step)
                        is ReadPhase.Timer -> PlainText(t, showRu = false, large = true)
                        else -> PlainText(t, showRu, large = false)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Surface(color = PravkaColors.Surface, shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (val p = phase) {
                            ReadPhase.Ready -> ReadyPanel(
                                t, sentences.size, own, showRu, onShowRu = { showRu = it },
                                onMic = { if (micGranted) startMic() else permission.launch(Manifest.permission.RECORD_AUDIO) },
                                onTimer = { stumbles = 0; elapsed = 0; phase = ReadPhase.Timer(SystemClock.elapsedRealtime()) },
                            )
                            is ReadPhase.Timer -> {
                                Text(fmtTime(elapsed), style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum", color = PravkaColors.Danger))
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BigButton(if (stumbles == 0) "Запинка" else "Запинка · $stumbles", onClick = { stumbles++ }, container = PravkaColors.Gold, modifier = Modifier.weight(1f))
                                    BigButton("СТОП", onClick = { stopTimer() }, container = PravkaColors.Danger, modifier = Modifier.weight(1f))
                                }
                            }
                            is ReadPhase.Saving -> {
                                Text(fmtTime(p.ms), style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum", color = PravkaColors.Ink))
                                Spacer(Modifier.height(8.dp))
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = PravkaColors.Good)
                            }
                            is ReadPhase.Mic -> MicPanel(
                                m = p, sentence = sentences.getOrNull(p.idx) ?: "", total = sentences.size, partial = partial, elapsed = elapsed,
                                onRetry = { speech.stop(); phase = p.copy(attempt = p.attempt + 1, hint = null, error = false) },
                                onSkip = {
                                    speech.stop()
                                    val d = drafts[p.idx]
                                    drafts[p.idx] = if (p.step == Step.READ) d.copy(enSkipped = true) else d.copy(ruSkipped = true)
                                    advance(p)
                                },
                                onFinish = {
                                    if (p.step == Step.TRANSLATE) drafts[p.idx] = drafts[p.idx].copy(ruSkipped = true)
                                    finishMic(if (p.step == Step.TRANSLATE) p.idx + 1 else p.idx)
                                },
                                onSpeak = {
                                    // Пока телефон читает сам, микрофон выключен, иначе он услышит собственный голос.
                                    speech.stop()
                                    phase = p.copy(hint = "Послушал? Жми «Ещё раз» и читай сам", error = false)
                                    sentences.getOrNull(p.idx)?.let { speaker.speak(it) }
                                },
                            )
                            ReadPhase.Judging -> {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = PravkaColors.Good)
                                Spacer(Modifier.height(8.dp))
                                Text("Opus слушает, как Боря прочитал и перевёл…", color = PravkaColors.Ink2, textAlign = TextAlign.Center)
                            }
                            is ReadPhase.Result -> ResultPanel(
                                p, own,
                                onCancel = { vm.deleteReadingRun(p.run.id); phase = ReadPhase.Ready },
                                onAgain = { phase = ReadPhase.Ready },
                            )
                        }
                    }
                }
            }
        }
        ConfettiOverlay(trigger = confetti, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun PlainText(t: Readable, showRu: Boolean, large: Boolean) {
    Text(
        t.textEn,
        style = TextStyle(fontSize = if (large) 26.sp else 21.sp, lineHeight = if (large) 40.sp else 32.sp, color = PravkaColors.Ink),
    )
    if (showRu && t.textRu.isNotBlank() && !large) {
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = PravkaColors.Grid)
        Spacer(Modifier.height(12.dp))
        Text(t.textRu, style = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, color = PravkaColors.Ink2))
    }
}

/** Текст по предложениям: текущее выделено цветом шага, прочитанные приглушены. */
@Composable
private fun SentenceText(sentences: List<String>, idx: Int, step: Step) {
    val annotated = buildAnnotatedString {
        sentences.forEachIndexed { i, s ->
            when {
                i == idx -> withStyle(
                    SpanStyle(
                        background = if (step == Step.READ) PravkaColors.EnSoft else PravkaColors.RuSoft,
                        color = PravkaColors.Ink, fontWeight = FontWeight.Bold,
                    ),
                ) { append(s) }
                i < idx -> withStyle(SpanStyle(color = PravkaColors.Muted)) { append(s) }
                else -> withStyle(SpanStyle(color = PravkaColors.Ink2)) { append(s) }
            }
            if (i + 1 < sentences.size) append(" ")
        }
    }
    Text(annotated, style = TextStyle(fontSize = 21.sp, lineHeight = 34.sp))
}

@Composable
private fun ReadyPanel(
    t: Readable, sentenceCount: Int, own: List<ReadingRun>, showRu: Boolean, onShowRu: (Boolean) -> Unit,
    onMic: () -> Unit, onTimer: () -> Unit,
) {
    val best = own.maxByOrNull { it.wordsPerMinute }
    val lastMic = own.lastOrNull { it.isMic }
    Text(
        buildString {
            append(plural(t.words, "слово", "слова", "слов")).append(" · ").append(plural(sentenceCount, "предложение", "предложения", "предложений"))
            if (best != null) append(" · лучшее ${best.wordsPerMinute.roundToInt()} сл/мин")
            if (lastMic?.transOk != null && lastMic.sentences != null) append(" · перевод ${lastMic.transOk}/${lastMic.sentences}")
        },
        style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Перевод", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2)
        Spacer(Modifier.width(6.dp))
        Switch(checked = showRu, onCheckedChange = onShowRu)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onTimer) { Text("Секундомер", color = PravkaColors.Ink2) }
    }
    Spacer(Modifier.height(6.dp))
    BigButton("ЧИТАТЬ С МИКРОФОНОМ", onClick = onMic, container = PravkaColors.Good)
    Spacer(Modifier.height(6.dp))
    Text(
        "Боря читает предложение по-английски, делает паузу, говорит перевод по-русски. Дальше приложение само переходит к следующему.",
        style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted, textAlign = TextAlign.Center,
    )
}

@Composable
private fun MicPanel(
    m: ReadPhase.Mic, sentence: String, total: Int, partial: String, elapsed: Long,
    onRetry: () -> Unit, onSkip: () -> Unit, onFinish: () -> Unit, onSpeak: () -> Unit,
) {
    val reading = m.step == Step.READ
    val accent by animateColorAsState(if (reading) PravkaColors.En else PravkaColors.Ru, label = "accent")
    val soft = if (reading) PravkaColors.EnSoft else PravkaColors.RuSoft
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${m.idx + 1} / $total", style = MaterialTheme.typography.labelLarge, color = PravkaColors.Muted)
        Spacer(Modifier.weight(1f))
        Text(fmtTime(elapsed), style = MaterialTheme.typography.labelLarge, color = PravkaColors.Muted)
    }
    Spacer(Modifier.height(6.dp))
    Text(
        if (reading) "Читай по-английски" else "Теперь скажи по-русски",
        style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = accent), textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        sentence,
        style = TextStyle(fontSize = if (sentence.length > 60) 20.sp else 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = PravkaColors.Ink),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    if (m.hint == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(44.dp).background(soft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Mic, contentDescription = "Микрофон", tint = accent)
            }
            Text(
                partial.ifBlank { if (m.attempt > 0) "Не услышал, скажи ещё раз" else "Слушаю…" },
                style = MaterialTheme.typography.bodyMedium, color = if (partial.isBlank()) PravkaColors.Muted else PravkaColors.Ink2,
                maxLines = 2,
            )
        }
    } else {
        Text(m.hint, color = if (m.error) PravkaColors.Danger else PravkaColors.Ink2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        BigButton("Ещё раз", onClick = onRetry, container = accent)
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reading) {
            SecondaryButton("Прочитать мне", onClick = onSpeak, modifier = Modifier.weight(1f))
        } else {
            SecondaryButton("Ещё раз", onClick = onRetry, modifier = Modifier.weight(1f))
        }
        SecondaryButton(if (reading) "Пропустить" else "Без перевода", onClick = onSkip, modifier = Modifier.weight(1f))
        SecondaryButton("Закончить", onClick = onFinish, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ResultPanel(p: ReadPhase.Result, own: List<ReadingRun>, onCancel: () -> Unit, onAgain: () -> Unit) {
    val run = p.run
    Text(
        "${run.wordsPerMinute.roundToInt()} слов в минуту",
        style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = if (p.isRecord) PravkaColors.GoodText else PravkaColors.Ink),
    )
    val pb = p.prevBest?.takeIf { it.wordsPerMinute > 0 }
    Text(
        buildString {
            append(if (run.isMic) "чтение ${fmtTime(run.readMs)}, всего ${fmtTime(run.durationMs)}" else "${fmtTime(run.durationMs)} · ${plural(run.stumbles, "запинка", "запинки", "запинок")}")
            if (pb != null) {
                val pct = ((run.wordsPerMinute / pb.wordsPerMinute - 1) * 100).roundToInt()
                if (pct > 0) append(" · быстрее лучшего на $pct%") else if (pct < 0) append(" · лучшее было ${pb.wordsPerMinute.roundToInt()}")
            }
        },
        style = MaterialTheme.typography.bodyMedium, color = PravkaColors.Ink2, textAlign = TextAlign.Center,
    )
    val d = p.detail
    if (d != null) {
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("чтение ${d.readOk}/${d.total}" + (if (d.readSlips > 0) " (+${d.readSlips} с заминкой)" else ""), PravkaColors.EnSoft, PravkaColors.EnText)
            if (d.translationKnown) Pill("перевод ${d.transOk}/${d.total}", PravkaColors.RuSoft, PravkaColors.RuText)
        }
        val prev = p.prevMic
        if (prev?.sentences != null && prev.sentences == d.total) {
            val parts = ArrayList<String>()
            prev.readOk?.let { if (d.readOk > it) parts += "чтение лучше на ${d.readOk - it}" else if (d.readOk < it) parts += "чтение было ${it}/${prev.sentences}" }
            prev.transOk?.let { if (d.translationKnown) { if (d.transOk > it) parts += "перевод точнее на ${d.transOk - it}" else if (d.transOk < it) parts += "перевод был ${it}/${prev.sentences}" } }
            if (parts.isNotEmpty()) Text("Против прошлого раза: " + parts.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2, textAlign = TextAlign.Center)
        }
        if (d.praise.isNotBlank()) Text(d.praise, color = PravkaColors.GoodText, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (p.warning != null) Text(p.warning, color = PravkaColors.GoldText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
    if (p.isRecord) Text("Новый рекорд чтения!", color = PravkaColors.GoldText, fontWeight = FontWeight.Bold)
    if (!run.isMic && run.stumbles == 0) Text("Без единой запинки", color = PravkaColors.GoodText, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Отменить", onClick = onCancel, modifier = Modifier.weight(1f))
        BigButton("Ещё раз", onClick = onAgain, modifier = Modifier.weight(1f))
    }
    if (own.size >= 2) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Чтения: " + own.takeLast(5).joinToString(" · ") { "${it.wordsPerMinute.roundToInt()}" } + " сл/мин",
            style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
        )
    }
}

/** Разбор по предложениям: цвет строки по чтению, значок по переводу, комментарий модели. */
@Composable
private fun ResultDetails(d: ReadingDetail) {
    Text("Разбор по предложениям", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    d.sentences.forEachIndexed { i, s ->
        val bg = when (s.reading) {
            SentenceResult.READ_OK -> Color.Transparent
            SentenceResult.READ_SLIPS -> PravkaColors.GoldSoft
            SentenceResult.SKIPPED -> PravkaColors.Surface2
            else -> Color(0xFFFBE3E3)
        }
        Column(Modifier.fillMaxWidth().background(bg, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = PravkaColors.Muted, modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.text, style = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, color = PravkaColors.Ink))
                    if (s.heardRu.isNotBlank() || s.translation == SentenceResult.SKIPPED) {
                        Text(
                            if (s.translation == SentenceResult.SKIPPED) "перевод пропущен" else "«${s.heardRu}»",
                            style = MaterialTheme.typography.bodySmall,
                            color = when (s.translation) {
                                SentenceResult.TR_OK -> PravkaColors.GoodText
                                SentenceResult.TR_PARTIAL -> PravkaColors.GoldText
                                SentenceResult.TR_WRONG -> PravkaColors.Danger
                                else -> PravkaColors.Muted
                            },
                        )
                    }
                    if (s.comment.isNotBlank()) Text(s.comment, style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2, modifier = Modifier.padding(top = 2.dp))
                }
                Text(
                    when (s.reading) {
                        SentenceResult.READ_OK -> "✓"
                        SentenceResult.READ_SLIPS -> "~"
                        SentenceResult.SKIPPED -> "–"
                        else -> "✗"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when (s.reading) {
                        SentenceResult.READ_OK -> PravkaColors.Good
                        SentenceResult.READ_SLIPS -> PravkaColors.Gold
                        SentenceResult.SKIPPED -> PravkaColors.Muted
                        else -> PravkaColors.Danger
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Цвет строки — чтение: белая чисто, жёлтая с заминкой, красная не получилось. Цвет перевода под предложением: зелёный верно, жёлтый наполовину, красный не то.",
        style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
    )
}
