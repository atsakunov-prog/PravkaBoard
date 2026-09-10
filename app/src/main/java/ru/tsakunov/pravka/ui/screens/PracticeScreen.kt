@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import ru.tsakunov.pravka.domain.Verdict
import ru.tsakunov.pravka.domain.VerdictKind
import ru.tsakunov.pravka.domain.countLetters
import ru.tsakunov.pravka.domain.evaluate
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
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val item = items.firstOrNull { it.id == itemId }
    val text = item?.let { if (lang == Lang.EN) it.en else it.ru } ?: ""
    val letters = countLetters(text)

    var phase by remember(itemId, lang) { mutableStateOf<Phase>(Phase.Ready) }
    var elapsed by remember(itemId, lang) { mutableLongStateOf(0L) }
    var confetti by remember { mutableIntStateOf(0) }

    // Экран не гаснет, пока Боря пишет.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
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
        scope.launch {
            val a = vm.recordAttempt(it, lang, ms)
            val verdict = evaluate(a, attempts + a)
            phase = Phase.Result(a, verdict)
            if (verdict.celebrate) {
                confetti++
                vibrate(context)
            }
        }
    }

    fun goNext() {
        val tasks = tasksOf(items)
        val idx = tasks.indexOfFirst { it.item.id == itemId && it.lang == lang }
        val ordered = if (idx >= 0) tasks.drop(idx + 1) + tasks.take(idx + 1) else tasks
        val next = ordered.firstOrNull { doneToday(attempts, it) == null }
        if (next == null) onAllDone() else onNext(next.item.id, next.lang)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = PravkaColors.Page,
            topBar = {
                TopAppBar(
                    navigationIcon = { BackIcon(onBack) },
                    title = { LangTag(lang, big = true) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                WordDisplay(text)
                Text(lettersWord(letters), color = PravkaColors.Muted, style = MaterialTheme.typography.bodyMedium)
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
                    is Phase.Ready, is Phase.Running -> {
                        Spacer(Modifier.weight(1f))
                        Text(
                            fmtTime(elapsed, tenths = true),
                            style = TextStyle(
                                fontSize = 88.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum",
                                color = if (p is Phase.Running) PravkaColors.Danger else PravkaColors.Ink, letterSpacing = (-2).sp,
                            ),
                        )
                        Spacer(Modifier.weight(1f))
                        if (p is Phase.Ready) {
                            HugeButton("СТАРТ", PravkaColors.Good, enabled = item != null && letters > 0) {
                                elapsed = 0
                                phase = Phase.Running(SystemClock.elapsedRealtime())
                            }
                        } else {
                            HugeButton("СТОП", PravkaColors.Danger) { stop() }
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
        AnimatedVisibility(visible = true, enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn()) {
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
