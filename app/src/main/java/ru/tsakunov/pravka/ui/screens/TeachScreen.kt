@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import ru.tsakunov.pravka.data.ActivityLog
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.wordsWord

/** Училка: русское слово, по нажатию открывается английское; «знал» / «ещё повторю». */
@Composable
fun TeachScreen(vm: AppViewModel, listId: String, onBack: () -> Unit, onTest: () -> Unit) {
    val all by remember(listId) { vm.observeItems(listId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val items = remember(all) { all.filter { it.en.isNotBlank() && it.ru.isNotBlank() } }
    val speaker = rememberSpeaker()

    // Очередь идентификаторов: невыученные слова возвращаются в конец круга.
    var queue by rememberSaveable(listId) { mutableStateOf(emptyList<String>()) }
    var revealed by rememberSaveable(listId) { mutableStateOf(false) }
    var knownFirstTry by rememberSaveable(listId) { mutableIntStateOf(0) }
    var repeats by rememberSaveable(listId) { mutableIntStateOf(0) }
    var seen by rememberSaveable(listId) { mutableStateOf(setOf<String>()) }
    // Очередь заполняется, когда слова загрузились, и только если круг ещё не начинался.
    LaunchedEffect(items) { if (queue.isEmpty() && seen.isEmpty() && items.isNotEmpty()) queue = items.map { it.id } }

    val current = queue.firstOrNull()?.let { id -> items.firstOrNull { it.id == id } }
    val total = items.size
    val done = total - queue.distinct().size

    // Круг пройден: одна запись в журнал занятий (слов, сразу знал, время круга).
    var roundStart by rememberSaveable(listId) { mutableLongStateOf(0L) }
    var roundLogged by rememberSaveable(listId) { mutableStateOf(false) }
    LaunchedEffect(current?.id, total) {
        if (current != null && roundStart == 0L) roundStart = System.currentTimeMillis()
        if (current == null && total > 0 && seen.isNotEmpty() && !roundLogged) {
            roundLogged = true
            val ms = if (roundStart > 0) System.currentTimeMillis() - roundStart else 0L
            vm.logActivity(ActivityLog.KIND_TEACH, listId, ms, total, knownFirstTry)
        }
    }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                navigationIcon = { BackIcon(onBack) },
                title = { Text("Училка", fontWeight = FontWeight.Bold) },
                actions = {
                    if (total > 0 && current != null) Text("осталось ${queue.distinct().size}", color = PravkaColors.Ink2, modifier = Modifier.padding(end = 16.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (total == 0) {
                EmptyHint("В уроке нет пар слово–перевод")
                return@Column
            }
            LinearProgressIndicator(
                progress = { done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = PravkaColors.Violet, trackColor = PravkaColors.Surface2,
            )
            Spacer(Modifier.weight(1f))
            if (current == null) {
                Text("Круг пройден", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Сразу знал ${wordsWord(knownFirstTry)} из $total" + (if (repeats > 0) ", ${wordsWord(repeats)} пришлось повторить" else "") + ".",
                    color = PravkaColors.Ink2, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                BigButton("На контрошу", onClick = onTest, container = PravkaColors.Ru)
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    "Ещё круг",
                    onClick = { queue = items.map { it.id }; revealed = false; knownFirstTry = 0; repeats = 0; seen = emptySet(); roundStart = 0L; roundLogged = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { if (!revealed) { revealed = true; speaker.speak(current.en) } },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = PravkaColors.Surface,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, PravkaColors.Border),
                ) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LangTag(Lang.RU)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            current.ru,
                            style = TextStyle(fontSize = if (current.ru.length > 14) 28.sp else 38.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.Ink),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider(color = PravkaColors.Grid)
                        Spacer(Modifier.height(18.dp))
                        if (revealed) {
                            LangTag(Lang.EN)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                current.en,
                                style = TextStyle(fontSize = if (current.en.length > 14) 28.sp else 38.sp, fontWeight = FontWeight.ExtraBold, color = PravkaColors.EnText),
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            IconButton(onClick = { speaker.speak(current.en) }, modifier = Modifier.size(48.dp).background(PravkaColors.EnSoft, CircleShape)) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Озвучить", tint = PravkaColors.EnText)
                            }
                        } else {
                            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                                Text("Нажми, чтобы увидеть по-английски", color = PravkaColors.Muted, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (revealed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            "Ещё повторю",
                            onClick = {
                                repeats++
                                seen = seen + current.id
                                queue = queue.drop(1) + current.id // в конец круга
                                revealed = false
                            },
                            modifier = Modifier.weight(1f),
                        )
                        BigButton(
                            "Знал",
                            onClick = {
                                if (current.id !in seen) knownFirstTry++
                                seen = seen + current.id
                                queue = queue.drop(1).filter { it != current.id }
                                revealed = false
                            },
                            container = PravkaColors.Good,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    BigButton("Показать", onClick = { revealed = true; speaker.speak(current.en) }, container = PravkaColors.Violet)
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
