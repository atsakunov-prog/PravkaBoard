@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.domain.Task
import ru.tsakunov.pravka.domain.Verdict
import ru.tsakunov.pravka.domain.VerdictKind
import ru.tsakunov.pravka.domain.attemptsToday
import ru.tsakunov.pravka.domain.countLetters
import ru.tsakunov.pravka.domain.evaluate
import ru.tsakunov.pravka.domain.nextTask
import ru.tsakunov.pravka.domain.statsFor
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtNum
import ru.tsakunov.pravka.ui.fmtTime
import ru.tsakunov.pravka.ui.lettersWord
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel

private sealed interface Phase {
    data object Ready : Phase
    data class Running(val startedAt: Long) : Phase
    /** СТОП нажат, запись ещё сохраняется; защищает от двойного нажатия. */
    data object Saving : Phase
    data class Result(val attempt: Attempt, val verdict: Verdict) : Phase
}

@Composable
fun PracticeScreen(
    vm: AppViewModel,
    listId: String,
    itemId: String,
    lang: Lang,
    onBack: () -> Unit,
    onNext: (itemId: String, lang: Lang) -> Unit,
    onAllDone: () -> Unit,
) {
    val items by vm.observeItems(listId).collectAsStateWithLifecycle(initialValue = emptyList())
    val attempts by vm.attempts.collectAsStateWithLifecycle()
    val undoStack by vm.undoStack.collectAsStateWithLifecycle()
    val cursive by vm.cursiveFonts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaker = rememberSpeaker()

    val item = items.firstOrNull { it.id == itemId }
    val task = item?.let { Task(it, lang) }
    val text = task?.text ?: ""
    val helper = task?.helper ?: ""
    val letters = countLetters(text)

    var phase by remember(itemId, lang) { mutableStateOf<Phase>(Phase.Ready) }
    var elapsed by remember(itemId, lang) { mutableLongStateOf(0L) }
    var confetti by remember { mutableIntStateOf(0) }

    // Режим по слову: ещё не писал сегодня — списывание, оба слова видны; уже писал (зелёное в списке) — по памяти:
    // видна подсказка, слово спрятано под плашкой, плашка его произносит, а показывается оно только после СТОПа,
    // чтобы Боря сверил написанное. Режим фиксируется до СТОПа: после записи счётчик вырастет, а экран не должен
    // перескочить.
    val todayCount = task?.let { attemptsToday(attempts, it) } ?: 0
    var pass by remember(itemId, lang) { mutableIntStateOf(todayCount) }
    LaunchedEffect(todayCount) { if (phase is Phase.Ready) pass = todayCount }
    val recall = pass >= 1 && helper.isNotBlank()
    /** Слово уже прозвучало: можно брать ручку. */
    var heard by remember(itemId, lang) { mutableStateOf(false) }
    /** Голоса для языка нет: вместо звука слово показывается, иначе писать нечего. */
    var shownInstead by remember(itemId, lang) { mutableStateOf(false) }
    val hidden = recall && phase !is Phase.Result && !shownInstead
    fun hear() {
        heard = true
        if (!speaker.speak(text, Speaker.localeOf(lang))) {
            shownInstead = true
            vm.showToast("На этом устройстве нет голоса для этого языка, показываю слово")
        }
    }

    // Тик таймера
    val running = phase as? Phase.Running
    LaunchedEffect(running) {
        if (running == null) return@LaunchedEffect
        while (true) {
            elapsed = SystemClock.elapsedRealtime() - running.startedAt
            delay(50)
        }
    }

    // Кнопка «назад» во время письма отменяет попытку.
    BackHandler(enabled = phase is Phase.Running) { phase = Phase.Ready; elapsed = 0 }

    val langStats = remember(attempts, lang) { statsFor(lang, attempts) }

    fun stop() {
        val r = phase as? Phase.Running ?: return
        val ms = (SystemClock.elapsedRealtime() - r.startedAt).coerceAtLeast(100)
        elapsed = ms
        val it = item ?: return
        phase = Phase.Saving
        scope.launch {
            val a = vm.recordAttempt(it, lang, ms)
            // Поток из Room мог уже успеть отдать новую запись: не считаем её дважды.
            val verdict = evaluate(a, attempts.filter { x -> x.id != a.id } + a)
            phase = Phase.Result(a, verdict)
            if (verdict.celebrate) {
                confetti++
                vibrate(context)
            }
        }
    }

    // «Дальше» держится за язык: сначала все ненаписанные слова этого языка, потом другой.
    fun goNext() {
        val next = nextTask(items, attempts, itemId, lang)
        if (next == null) onAllDone() else onNext(next.item.id, next.lang)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = PravkaColors.Page,
            topBar = {
                TopAppBar(
                    navigationIcon = { BackIcon(onBack) },
                    title = { LangTag(lang, big = true) },
                    actions = {
                        // До СТАРТа можно снять предыдущий результат (например, СТОП нажали не вовремя и уже ушли «Дальше»).
                        // Пока показан свой результат, для него есть кнопка «Отменить» ниже.
                        if (phase is Phase.Ready && undoStack.any { it.listId == listId }) {
                            IconButton(onClick = { vm.undoLast(listId) }) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Отменить последнее действие", tint = PravkaColors.Ink)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                if (hidden) {
                    // По памяти: как завёрнутый столбик гармошки. Видна подсказка, слово под плашкой:
                    // Боря переводит вслух, нажимает, слышит слово и пишет его; увидит только после СТОПа.
                    HelperWord(helper, lang.other(), big = true)
                    Spacer(Modifier.height(14.dp))
                    CoverPlate(lang, heard, onHear = { hear() })
                } else {
                    WordDisplay(text)
                    // Как это пишется в тетради: прописью, со всеми соединениями.
                    if (text.isNotBlank()) CursiveWord(text, lang, cursive, Modifier.padding(bottom = 4.dp))
                    // Перевод мелкими буквами: иначе слово просто переписывается, не связываясь со смыслом.
                    if (helper.isNotBlank()) HelperWord(helper, lang.other(), big = false)
                    Text(lettersWord(letters), color = PravkaColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                if (langStats.n > 0) {
                    Text(
                        buildString {
                            langStats.best?.let { append("Рекорд ${fmtNum(it.secPerLetter)}") }
                            langStats.avg?.let { if (isNotEmpty()) append(" · "); append("Среднее ${fmtNum(it)}") }
                            append(" с/букву")
                        },
                        color = PravkaColors.Ink2, style = MaterialTheme.typography.bodyMedium,
                    )
                }

                when (val p = phase) {
                    is Phase.Ready, is Phase.Running, is Phase.Saving -> {
                        Spacer(Modifier.weight(1f))
                        Text(
                            fmtTime(elapsed, tenths = true),
                            style = TextStyle(
                                fontSize = 76.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum",
                                color = if (p is Phase.Running) PravkaColors.Danger else PravkaColors.Ink, letterSpacing = (-2).sp,
                            ),
                            maxLines = 1, softWrap = false,
                        )
                        Spacer(Modifier.weight(1f))
                        when (p) {
                            // По памяти СТАРТ ждёт, пока слово прозвучит: сначала перевод вслух и звук, потом ручка.
                            is Phase.Ready -> HugeButton("СТАРТ", PravkaColors.Good, enabled = item != null && letters > 0 && (!recall || heard || shownInstead)) {
                                elapsed = 0
                                phase = Phase.Running(SystemClock.elapsedRealtime())
                            }
                            is Phase.Running -> HugeButton("СТОП", PravkaColors.Danger) { stop() }
                            else -> HugeButton("…", PravkaColors.Danger, enabled = false) {}
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    is Phase.Result -> {
                        Spacer(Modifier.height(18.dp))
                        ResultBlock(p.attempt, p.verdict)
                        Spacer(Modifier.weight(1f))
                        BigButton("Дальше", onClick = { goNext() })
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(
                                "Отменить",
                                onClick = { vm.deleteAttempt(p.attempt.id); phase = Phase.Ready; elapsed = 0 },
                                modifier = Modifier.weight(1f),
                            )
                            SecondaryButton("К списку", onClick = onBack, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
        ConfettiOverlay(trigger = confetti, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun WordDisplay(text: String) {
    val size = when {
        text.length <= 5 -> 72.sp
        text.length <= 8 -> 56.sp
        text.length <= 14 -> 40.sp
        text.length <= 24 -> 30.sp
        else -> 24.sp
    }
    Text(
        text,
        style = TextStyle(fontSize = size, fontWeight = FontWeight.ExtraBold, lineHeight = size * 1.1, color = PravkaColors.Ink),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

/** Слово на другом языке: крупно, когда это единственная подсказка, мелко под словом в круге списывания. */
@Composable
private fun HelperWord(text: String, lang: Lang, big: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LangTag(lang, big = big)
        Spacer(Modifier.height(if (big) 10.dp else 4.dp))
        val size = when {
            !big -> if (text.length > 14) 20.sp else 26.sp
            text.length <= 8 -> 48.sp
            text.length <= 14 -> 36.sp
            else -> 28.sp
        }
        Text(
            text,
            style = TextStyle(
                fontSize = size, lineHeight = size * 1.15,
                fontWeight = if (big) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = if (big) PravkaColors.Ink else PravkaColors.Ink2,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

/** Плашка на месте спрятанного слова: нажатие произносит его, само слово откроется после СТОПа. */
@Composable
private fun CoverPlate(lang: Lang, heard: Boolean, onHear: () -> Unit) {
    Surface(
        onClick = onHear,
        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = lang.softColor(),
        border = BorderStroke(2.dp, lang.color()),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = lang.textColor(), modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    heard -> "Послушать ещё раз. Слово откроется после СТОПа"
                    lang == Lang.EN -> "Скажи по-английски, потом нажми и послушай"
                    else -> "Скажи по-русски, потом нажми и послушай"
                },
                color = lang.textColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HugeButton(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(96.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Text(label, style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp))
    }
}

@Composable
private fun ResultBlock(attempt: Attempt, v: Verdict) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            fmtTime(attempt.ms, tenths = true),
            style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum", color = PravkaColors.Ink),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtNum(v.rate), style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.Ink))
            Text("  с/букву", style = MaterialTheme.typography.titleMedium, color = PravkaColors.Ink2, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(14.dp))
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(visibleState = shown, enter = if (PravkaColors.reader) EnterTransition.None else scaleIn(spring(dampingRatio = 0.5f)) + fadeIn()) {
            val (bg, fg, label) = when (v.kind) {
                VerdictKind.RECORD -> Triple(PravkaColors.GoldSoft, PravkaColors.GoldText, "НОВЫЙ РЕКОРД!")
                VerdictKind.FASTER -> Triple(PravkaColors.GoodSoft, PravkaColors.GoodText, "Быстрее среднего")
                VerdictKind.FIRST -> Triple(PravkaColors.EnSoft, PravkaColors.EnText, "Первый результат записан")
                VerdictKind.NEUTRAL -> Triple(PravkaColors.Surface2, PravkaColors.Ink2, "Записано")
            }
            Box(Modifier.background(bg, CircleShape).padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = if (v.kind == VerdictKind.RECORD) 20.sp else 16.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        val details = buildList {
            if (v.kind == VerdictKind.RECORD && v.prevBest != null) add("Было ${fmtNum(v.prevBest)}, стало ${fmtNum(v.rate)} с/букву")
            if (v.kind == VerdictKind.FASTER && v.avg != null) {
                val pct = ((1 - v.rate / v.avg) * 100).toInt()
                add("На $pct% быстрее среднего (${fmtNum(v.avg)})")
            }
            if (v.kind == VerdictKind.NEUTRAL && v.avg != null) add("Среднее ${fmtNum(v.avg)} с/букву. Следующее слово будет быстрее!")
            if (v.streak >= 3) add("Серия: ${v.streak} слов подряд быстрее среднего")
        }
        details.forEach { Text(it, color = PravkaColors.Ink2, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
        v.milestone?.let {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().background(PravkaColors.GoldSoft, MaterialTheme.shapes.medium).padding(12.dp), contentAlignment = Alignment.Center) {
                Text("Уже ${lettersWord(it)} написано за всё время!", color = PravkaColors.GoldText, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun vibrate(context: android.content.Context) {
    val v = context.getSystemService(Vibrator::class.java) ?: return
    runCatching { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 50, 70, 50, 180), -1)) }
}
