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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
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
import kotlinx.coroutines.launch
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
    /** attempt растёт при каждом перезапуске прослушивания, чтобы эффект точно перезапускался. */
    data class Listening(val idx: Int, val attempt: Int = 0, val partial: String = "", val hint: String? = null) : TestPhase
    data class Checking(val idx: Int, val heard: List<String>) : TestPhase
    data class Correct(val idx: Int, val heard: String) : TestPhase
    data class Wrong(val idx: Int, val heard: String) : TestPhase
    data class Done(val durationMs: Long) : TestPhase
    data class Error(val idx: Int, val message: String) : TestPhase
}

/** Контроша: русское слово, микрофон, английский ответ. Ошибка — всё с начала. */
@Composable
fun TestScreen(vm: AppViewModel, listId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by remember(listId) { vm.observeItems(listId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val items = remember(all) { all.filter { it.en.isNotBlank() && it.ru.isNotBlank() } }
    val speech = remember { SpeechInput(context) }
    val speaker = rememberSpeaker()

    var phase by remember { mutableStateOf<TestPhase>(TestPhase.Ready) }
    var attempts by remember { mutableIntStateOf(1) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var confetti by remember { mutableIntStateOf(0) }
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) phase = TestPhase.Listening(0) else vm.showToast("Без микрофона контроша не работает")
    }

    DisposableEffect(Unit) { onDispose { speech.release() } }

    fun listen(idx: Int) {
        speech.start(
            language = "en-US",
            onPartial = { p -> (phase as? TestPhase.Listening)?.let { if (it.idx == idx) phase = it.copy(partial = p) } },
            onResult = { heard -> if ((phase as? TestPhase.Listening)?.idx == idx) phase = TestPhase.Checking(idx, heard) },
            onError = { code ->
                val cur = phase as? TestPhase.Listening ?: return@start
                if (cur.idx != idx) return@start
                phase = if (SpeechInput.isRetryable(code)) TestPhase.Listening(idx, attempt = cur.attempt + 1, hint = SpeechInput.describeError(code))
                else TestPhase.Error(idx, SpeechInput.describeError(code))
            },
        )
    }

    // Запуск прослушивания при входе в Listening (в том числе повтор после «не услышал»).
    val listening = phase as? TestPhase.Listening
    LaunchedEffect(listening?.idx, listening?.attempt) {
        if (listening == null) return@LaunchedEffect
        if (listening.attempt > 0) delay(600)
        listen(listening.idx)
    }

    // Проверка ответа: сначала локально, для фраз — с запасным судьёй в модели.
    // Эффект только выставляет Correct/Wrong: смена фазы меняет ключ и отменяет его.
    val checking = phase as? TestPhase.Checking
    LaunchedEffect(checking) {
        if (checking == null) return@LaunchedEffect
        val item = items.getOrNull(checking.idx) ?: return@LaunchedEffect
        val heardText = checking.heard.firstOrNull() ?: ""
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
        if (correct.idx + 1 < items.size) phase = TestPhase.Listening(correct.idx + 1)
        else {
            val duration = SystemClock.elapsedRealtime() - startedAt
            phase = TestPhase.Done(duration)
            confetti++
            vm.saveQuizRun(listId, attempts, duration, items.size)
        }
    }

    fun begin() {
        if (!speech.available) { vm.showToast("На телефоне нет службы распознавания речи Google"); return }
        attempts = 1
        startedAt = SystemClock.elapsedRealtime()
        if (micGranted) phase = TestPhase.Listening(0) else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun restart() {
        attempts++
        speech.stop()
        phase = TestPhase.Listening(0)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = PravkaColors.Page,
            topBar = {
                TopAppBar(
                    navigationIcon = { BackIcon { speech.stop(); onBack() } },
                    title = { Text("Контроша", fontWeight = FontWeight.Bold) },
                    actions = {
                        val idx = when (val p = phase) {
                            is TestPhase.Listening -> p.idx; is TestPhase.Checking -> p.idx; is TestPhase.Correct -> p.idx
                            is TestPhase.Wrong -> p.idx; is TestPhase.Error -> p.idx; else -> -1
                        }
                        if (idx >= 0) Text("${idx + 1} / ${items.size} · попытка $attempts", color = PravkaColors.Ink2, modifier = Modifier.padding(end = 16.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (items.isEmpty()) { EmptyHint("В уроке нет пар слово–перевод"); return@Column }
                val idx = when (val p = phase) {
                    is TestPhase.Listening -> p.idx; is TestPhase.Checking -> p.idx; is TestPhase.Correct -> p.idx
                    is TestPhase.Wrong -> p.idx; is TestPhase.Error -> p.idx; is TestPhase.Done -> items.size; else -> 0
                }
                LinearProgressIndicator(
                    progress = { idx.toFloat() / items.size },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = PravkaColors.Ru, trackColor = PravkaColors.Surface2,
                )
                Spacer(Modifier.weight(1f))

                when (val p = phase) {
                    TestPhase.Ready -> {
                        Text("Контроша по ${wordsWord(items.size)}", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "На экране русское слово, ты говоришь его по-английски. Одна ошибка — и начинаем с первого слова.",
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
                            is TestPhase.Listening -> {
                                MicIndicator(active = true)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    p.partial.ifBlank { p.hint ?: "Слушаю…" },
                                    color = if (p.hint != null && p.partial.isBlank()) PravkaColors.RuText else PravkaColors.Ink2,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            is TestPhase.Checking -> {
                                CircularProgressIndicator(color = PravkaColors.En)
                                Spacer(Modifier.height(8.dp))
                                Text("Проверяю: «${p.heard.firstOrNull() ?: ""}»", color = PravkaColors.Ink2, textAlign = TextAlign.Center)
                            }
                            is TestPhase.Correct -> Text("Верно!", style = MaterialTheme.typography.headlineSmall, color = PravkaColors.GoodText)
                            is TestPhase.Wrong -> {
                                Text("Ты сказал: «${p.heard}»", color = PravkaColors.Ink2, textAlign = TextAlign.Center)
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
                                    BigButton("Ещё раз", onClick = { phase = TestPhase.Listening(p.idx) }, modifier = Modifier.weight(1f))
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (phase !is TestPhase.Ready && phase !is TestPhase.Done) {
                    TextButton(onClick = { speech.stop(); phase = TestPhase.Ready }) { Text("Остановить", color = PravkaColors.Muted) }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        ConfettiOverlay(trigger = confetti, modifier = Modifier.fillMaxSize())
    }
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

@Composable
private fun MicIndicator(active: Boolean) {
    Box(
        Modifier.size(72.dp).background(if (active) PravkaColors.RuSoft else PravkaColors.Surface2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null, tint = if (active) PravkaColors.Ru else PravkaColors.Muted, modifier = Modifier.size(34.dp))
    }
}
