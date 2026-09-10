@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import android.os.SystemClock
import ru.tsakunov.pravka.data.ActivityLog
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/** Обучалка: карточка «слово + перевод», смахивание влево/вправо, озвучка. */
@Composable
fun LearnScreen(vm: AppViewModel, listId: String, onBack: () -> Unit, onTeach: () -> Unit) {
    val all by remember(listId) { vm.observeItems(listId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val items = remember(all) { all.filter { it.en.isNotBlank() && it.ru.isNotBlank() } }
    val speaker = rememberSpeaker()
    var index by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }

    // В журнал занятий: сколько карточек посмотрел и сколько времени провёл, пишется при уходе с экрана.
    val startedAt = remember(listId) { SystemClock.elapsedRealtime() }
    var maxIndex by remember(listId) { mutableIntStateOf(0) }
    LaunchedEffect(index, finished) { maxIndex = maxOf(maxIndex, if (finished) items.size else index + 1) }
    DisposableEffect(listId) {
        onDispose {
            val viewed = minOf(maxIndex, items.size)
            if (viewed > 0) vm.logActivity(ActivityLog.KIND_LEARN, listId, SystemClock.elapsedRealtime() - startedAt, viewed, viewed)
        }
    }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                navigationIcon = { BackIcon(onBack) },
                title = { Text("Обучалка", fontWeight = FontWeight.Bold) },
                actions = {
                    if (items.isNotEmpty() && !finished) {
                        Text("${index + 1} / ${items.size}", color = PravkaColors.Ink2, modifier = Modifier.padding(end = 16.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (items.isEmpty()) {
                EmptyHint("В уроке нет пар слово–перевод")
                return@Column
            }
            LinearProgressIndicator(
                progress = { if (finished) 1f else index.toFloat() / items.size },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = PravkaColors.En, trackColor = PravkaColors.Surface2,
            )
            Spacer(Modifier.weight(1f))
            if (finished) {
                Text("Все слова просмотрены", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Теперь проверь себя в училке.", color = PravkaColors.Ink2, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                BigButton("В училку", onClick = onTeach, container = PravkaColors.Violet)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Ещё раз с начала", onClick = { index = 0; finished = false }, modifier = Modifier.fillMaxWidth())
            } else {
                val item = items.getOrNull(index)
                if (item == null) { LaunchedEffect(Unit) { index = 0 }; return@Column }
                SwipeCard(
                    key = item.id,
                    onSwiped = { dir ->
                        speaker.stop()
                        if (dir > 0) {
                            if (index > 0) { index--; true } else false
                        } else {
                            if (index + 1 < items.size) index++ else finished = true
                            true
                        }
                    },
                ) {
                    LearnCardContent(item, onSpeak = { speaker.speak(item.en) })
                }
                Spacer(Modifier.height(16.dp))
                Text("Смахни карточку влево, чтобы перейти дальше", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Назад", onClick = { if (index > 0) index-- }, modifier = Modifier.weight(1f), enabled = index > 0)
                    BigButton(
                        if (index + 1 < items.size) "Дальше" else "Готово",
                        onClick = { speaker.stop(); if (index + 1 < items.size) index++ else finished = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LearnCardContent(item: WordItem, onSpeak: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LangTag(ru.tsakunov.pravka.data.Lang.EN)
        Spacer(Modifier.height(14.dp))
        Text(
            item.en,
            style = TextStyle(fontSize = if (item.en.length > 14) 30.sp else 42.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.Ink, lineHeight = 46.sp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        IconButton(onClick = onSpeak, modifier = Modifier.size(56.dp).background(PravkaColors.EnSoft, CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Озвучить", tint = PravkaColors.EnText)
        }
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = PravkaColors.Grid)
        Spacer(Modifier.height(18.dp))
        LangTag(ru.tsakunov.pravka.data.Lang.RU)
        Spacer(Modifier.height(10.dp))
        Text(
            item.ru,
            style = TextStyle(fontSize = if (item.ru.length > 14) 24.sp else 32.sp, fontWeight = FontWeight.Bold, color = PravkaColors.Ink2),
            textAlign = TextAlign.Center,
        )
    }
}

/** Карточка со смахиванием: onSwiped(+1) вправо, onSwiped(-1) влево; вернуть false, если смахивать некуда. */
@Composable
fun SwipeCard(key: Any, onSwiped: (Int) -> Boolean, content: @Composable () -> Unit) {
    val offsetX = remember(key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(1) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .graphicsLayer { rotationZ = offsetX.value / 40f; alpha = 1f - (abs(offsetX.value) / (width * 1.6f)).coerceIn(0f, 0.5f) }
            .pointerInput(key) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = width * 0.28f
                        val v = offsetX.value
                        scope.launch {
                            if (abs(v) > threshold) {
                                val dir = if (v > 0) 1 else -1
                                // Сначала спрашиваем, есть ли куда листать, и только тогда улетаем.
                                if (onSwiped(dir)) {
                                    offsetX.snapTo(0f)
                                } else {
                                    offsetX.animateTo(0f, tween(200))
                                }
                            } else {
                                offsetX.animateTo(0f, tween(200))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, tween(200)) } },
                ) { _, dragAmount -> scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetX.snapTo(offsetX.value + dragAmount) } }
            },
        shape = MaterialTheme.shapes.extraLarge,
        color = PravkaColors.Surface,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, PravkaColors.Border),
    ) {
        content()
    }
}
