@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.domain.Matching
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtTime
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.wordsWord

private sealed interface TestPhase {
    data object Ready : TestPhase
    /** Слово на экране, микрофон выключен: ждём нажатия на кнопку. hint — почему прошлый раз не вышло. */
    data class Prompt(val idx: Int, val hint: String? = null) : TestPhase
    /** Микрофон включён по кнопке; второе нажатие заканчивает реплику. */
    data class Listening(val idx: Int, val partial: String = "") : TestPhase
    data class Checking(val idx: Int, val heard: List<String>) : TestPhase
    data class Correct(val idx: Int, val heard: String) : TestPhase
    data class Wrong(val idx: Int, val heard: String) : TestPhase
    data class Done(val durationMs: Long) : TestPhase
    data class Error(val idx: Int, val message: String) : TestPhase
}

/**
 * Контроша: русское слово, ответ по-английски в микрофон. Ошибка — всё с начала.
 * Микрофон включается кнопкой и выключается второй кнопкой (или сам, когда ребёнок замолчал):
 * так в запись не попадают разговоры вокруг, а ребёнок сам решает, когда готов.
 */
@Composable
fun TestScreen(vm: AppViewModel, listId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val all by remember(listId) { vm.observeItems(listId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val items = remember(all) { all.filter { it.en.isNotBlank() && it.ru.isNotBlank() } }
    val speech = remember { vm.speechInput(context) }
    val speaker = rememberSpeaker()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    // С микрофоном — ответ распознаёт Google; без — Боря говорит вслух, а папа жмёт «верно» или «не верно».
    val withMic = settings.quizMic
    var revealedFor by remember { mutableIntStateOf(-1) }

    var phase by remember { mutableStateOf<TestPhase>(TestPhase.Ready) }
    var attempts by remember { mutableIntStateOf(1) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var confetti by remember { mutableIntStateOf(0) }
    var route by remember { mutableStateOf<MicRoute?>(null) }
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) phase = TestPhase.Prompt(0) else vm.showToast("Без микрофона контроша не работает")
    }

    DisposableEffect(Unit) { onDispose { speech.release() } }

    // Слова урока как подсказка распознавателю: он склоняется к ним, а не к похожим по звучанию.
    val biasing = remember(items) { items.flatMap { Matching.expectedVariants(it.en) + it.en }.distinct() }

    fun listen(idx: Int) {
        val m = TestPhase.Listening(idx)
        phase = m
        route = speech.route()
        speech.start(
            language = "en-US",
            biasing = biasing,
            onPartial = { p -> (phase as? TestPhase.Listening)?.let { if (it.idx == idx) phase = it.copy(partial = p) } },
            onResult = { heard -> if ((phase as? TestPhase.Listening)?.idx == idx) phase = TestPhase.Checking(idx, heard) },
            onError = { code ->
                val cur = phase as? TestPhase.Listening ?: return@start
                if (cur.idx != idx) return@start
                phase = if (SpeechInput.isRetryable(code)) TestPhase.Prompt(idx, hint = "Не услышал. Нажми и скажи ещё раз")
                else TestPhase.Error(idx, SpeechInput.describeError(code))
            },
        )
    }

    // Проверка ответа: сначала локально, для фраз — с запасным судьёй в модели.
    // Эффект только выставляет Correct/Wrong: смена фазы меняет ключ и отменяет его.
    val checking = phase as? TestPhase.Checking
    LaunchedEffect(checking) {
        if (checking == null) return@LaunchedEffect
        val item = items.getOrNull(checking.idx) ?: return@LaunchedEffect
        val heardText = checking.heard.firstOrNull() ?: ""
        if (checking.heard.all { it.isBlank() }) { phase = TestPhase.Prompt(checking.idx, hint = "Не услышал. Нажми и скажи ещё раз"); return@LaunchedEffect }
        var ok = Matching.matches(item.en, checking.heard)
        if (!ok && Matching.isPhrase(item.en)) ok = vm.judgeAnswer(item.ru, item.en, checking.heard)
        if (ok) phase = TestPhase.Correct(checking.idx, heardText)
        else {
            phase = TestPhase.Wrong(checking.idx, heardText)
            speaker.speak(item.en)
        }
    }

    // После «Верно!» пауза и переход к следующему слову или финиш.
    val correct = phase as? TestPhase.Correct
    LaunchedEffect(correct?.idx) {
        if (correct == null) return@LaunchedEffect
        delay(900)
        if (correct.idx + 1 < items.size) phase = TestPhase.Prompt(correct.idx + 1)
        else {
            val duration = SystemClock.elapsedRealtime() - startedAt
            phase = TestPhase.Done(duration)
            confetti++
            vm.saveQuizRun(listId, attempts, duration, items.size)
        }
    }

    fun begin() {
        attempts = 1
        startedAt = SystemClock.elapsedRealtime()
        revealedFor = -1
        if (!withMic) { phase = TestPhase.Prompt(0); return }
        if (!speech.available) { vm.showToast("На телефоне нет службы распознавания речи Google"); return }
        route = speech.route()
        if (micGranted) phase = TestPhase.Prompt(0) else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun restart() {
        attempts++
        speech.stop()
        revealedFor = -1
        phase = TestPhase.Prompt(0)
    }

    /** Без микрофона: вердикт папы. */
    fun verdict(idx: Int, ok: Boolean) {
        val item = items.getOrNull(idx) ?: return
        revealedFor = -1
        if (ok) phase = TestPhase.Correct(idx, "")
        else { phase = TestPhase.Wrong(idx, ""); speaker.speak(item.en) }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = PravkaColors.Page,
            topBar = {
                TopAppBar(
                    navigationIcon = { BackIcon { speech.stop(); onBack() } },
                    title = { Text("Контроша", fontWeight = FontWeight.Bold) },
                    actions = {
                        val idx = phase.index()
                        if (idx >= 0 && phase !is TestPhase.Done) Text("${idx + 1} / ${items.size} · попытка $attempts", color = PravkaColors.Ink2, modifier = Modifier.padding(end = 16.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (items.isEmpty()) { EmptyHint("В уроке нет пар слово–перевод"); return@Column }
                val idx = when (val p = phase) { is TestPhase.Done -> items.size; TestPhase.Ready -> 0; else -> p.index() }
                LinearProgressIndicator(
                    progress = { idx.toFloat() / items.size },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = PravkaColors.Ru, trackColor = PravkaColors.Surface2,
                )
                Spacer(Modifier.weight(1f))

                when (val p = phase) {
                    TestPhase.Ready -> {
                        Text("Контроша по ${wordsWord(items.size)}", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = withMic, onClick = { vm.setQuizMic(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2),
                                colors = SegmentedButtonDefaults.colors(activeContainerColor = PravkaColors.Ink, activeContentColor = Color.White, inactiveContainerColor = PravkaColors.Surface),
                            ) { Text("С микрофоном") }
                            SegmentedButton(
                                selected = !withMic, onClick = { vm.setQuizMic(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                                colors = SegmentedButtonDefaults.colors(activeContainerColor = PravkaColors.Ink, activeContentColor = Color.White, inactiveContainerColor = PravkaColors.Surface),
                            ) { Text("Без микрофона") }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (withMic) "На экране русское слово. Нажми на микрофон, скажи его по-английски, нажми ещё раз. Одна ошибка — и начинаем с первого слова."
                            else "На экране русское слово. Боря говорит его по-английски вслух, папа жмёт «верно» или «не верно». Одна ошибка — и начинаем с первого слова.",
                            color = PravkaColors.Ink2, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        BigButton("Начать", onClick = { begin() }, container = PravkaColors.Ru)
                    }
                    is TestPhase.Done -> {
                        Text("Пройдено!", style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.GoodText))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${wordsWord(items.size)} без ошибок, с ${attempts}-й попытки, за ${fmtTime(p.durationMs)}.",
                            color = PravkaColors.Ink2, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        BigButton("Ещё раз", onClick = { begin() })
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("К уроку", onClick = onBack, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        val item = items.getOrNull(idx)
                        if (item == null) {
                            // Список изменился под ногами: начинаем заново.
                            LaunchedEffect(Unit) { speech.stop(); phase = TestPhase.Ready }
                            return@Column
                        }
                        WordCard(item, phase)
                        Spacer(Modifier.height(20.dp))
                        when (p) {
                            is TestPhase.Prompt -> if (withMic) {
                                MicButton(listening = false, onClick = { listen(p.idx) })
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    p.hint ?: "Нажми и скажи по-английски",
                                    color = if (p.hint != null) PravkaColors.RuText else PravkaColors.Ink2, textAlign = TextAlign.Center,
                                )
                            } else {
                                Text("Боря говорит по-английски, папа отмечает", color = PravkaColors.Ink2, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(6.dp))
                                if (revealedFor == p.idx) {
                                    Text(item.en, style = MaterialTheme.typography.titleLarge, color = PravkaColors.EnText, textAlign = TextAlign.Center)
                                } else {
                                    TextButton(onClick = { revealedFor = p.idx; speaker.speak(item.en) }) { Text("Показать ответ", color = PravkaColors.Muted) }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CalmButton("Не верно", bg = Color(0xFFF3DCDC), fg = Color(0xFF8B3A3A), onClick = { verdict(p.idx, false) }, modifier = Modifier.weight(1f))
                                    CalmButton("Верно", bg = PravkaColors.GoodSoft, fg = PravkaColors.GoodText, onClick = { verdict(p.idx, true) }, modifier = Modifier.weight(1f))
                                }
                            }
                            is TestPhase.Listening -> {
                                MicButton(listening = true, onClick = { speech.finish() })
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    p.partial.ifBlank { "Слушаю… Сказал — нажми ещё раз" },
                                    color = PravkaColors.Ink2, textAlign = TextAlign.Center,
                                )
                            }
                            is TestPhase.Checking -> {
                                CircularProgressIndicator(color = PravkaColors.En)
                                Spacer(Modifier.height(8.dp))
                                Text("Проверяю: «${p.heard.firstOrNull() ?: ""}»", color = PravkaColors.Ink2, textAlign = TextAlign.Center)
                            }
                            is TestPhase.Correct -> Text("Верно!", style = MaterialTheme.typography.headlineSmall, color = PravkaColors.GoodText)
                            is TestPhase.Wrong -> {
                                if (p.heard.isNotBlank()) Text("Ты сказал: «${p.heard}»", color = PravkaColors.Ink2, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(4.dp))
                                Text("Правильно: ${item.en}", style = MaterialTheme.typography.titleMedium, color = PravkaColors.Danger, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(16.dp))
                                BigButton("Заново с первого слова", onClick = { restart() }, container = PravkaColors.Ru)
                            }
                            is TestPhase.Error -> {
                                Text(p.message, color = PravkaColors.Danger, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SecondaryButton("К уроку", onClick = onBack, modifier = Modifier.weight(1f))
                                    BigButton("Ещё раз", onClick = { phase = TestPhase.Prompt(p.idx) }, modifier = Modifier.weight(1f))
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (phase !is TestPhase.Ready && phase !is TestPhase.Done) {
                    if (withMic) route?.let { r ->
                        Text(
                            r.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (r.externalName != null && !r.forcedPhoneMic) PravkaColors.GoldText else PravkaColors.Muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                    TextButton(onClick = { speech.stop(); phase = TestPhase.Ready }) { Text("Остановить", color = PravkaColors.Muted) }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        ConfettiOverlay(trigger = confetti, modifier = Modifier.fillMaxSize())
    }
}

private fun TestPhase.index(): Int = when (this) {
    is TestPhase.Prompt -> idx; is TestPhase.Listening -> idx; is TestPhase.Checking -> idx; is TestPhase.Correct -> idx
    is TestPhase.Wrong -> idx; is TestPhase.Error -> idx; else -> -1
}

@Composable
private fun WordCard(item: WordItem, phase: TestPhase) {
    val target = when (phase) {
        is TestPhase.Correct -> PravkaColors.GoodSoft
        is TestPhase.Wrong -> Color(0xFFFBDADA)
        else -> PravkaColors.Surface
    }
    val bg by animateColorAsState(target, label = "card")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = bg,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, PravkaColors.Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LangTag(Lang.RU)
            Spacer(Modifier.height(14.dp))
            Text(
                item.ru,
                style = TextStyle(fontSize = if (item.ru.length > 14) 30.sp else 40.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.Ink),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Спокойная кнопка вердикта: мягкий фон, тёмный текст, без кричащих цветов. */
@Composable
fun CalmButton(text: String, bg: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 60.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        elevation = null,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** Большая круглая кнопка микрофона: серая — нажми и говори, красная с квадратом — говоришь, нажми, когда сказал. */
@Composable
fun MicButton(listening: Boolean, onClick: () -> Unit, size: Int = 96) {
    val bg by animateColorAsState(if (listening) PravkaColors.Danger else PravkaColors.Ru, label = "mic")
    Box(
        Modifier.size(size.dp).background(bg, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (listening) "Сказал" else "Говорить",
            tint = Color.White, modifier = Modifier.size((size * 0.45f).dp),
        )
    }
}
