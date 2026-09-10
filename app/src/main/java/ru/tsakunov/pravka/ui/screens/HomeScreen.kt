@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.data.WordListWithCount
import ru.tsakunov.pravka.domain.isToday
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.fmtDate
import ru.tsakunov.pravka.ui.lettersWord
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.vm.ParseState
import ru.tsakunov.pravka.ui.wordsWord
import java.io.File

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenList: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onTab: (Tab) -> Unit,
) {
    val context = LocalContext.current
    val lists by vm.lists.collectAsStateWithLifecycle()
    val attempts by vm.attempts.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val parse by vm.parse.collectAsStateWithLifecycle()

    val pending = remember { mutableStateListOf<Uri>() }
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<WordListWithCount?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = cameraTarget
        if (ok && target != null) pending += target
        cameraTarget = null
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        pending += uris
    }

    fun openCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "page_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraTarget = uri
        takePicture.launch(uri)
    }

    // Итог разбора фото
    LaunchedEffect(parse) {
        val p = parse
        if (p is ParseState.Done) {
            pending.clear()
            vm.parseHandled()
            onOpenList(p.list.id)
        }
    }

    val todayAttempts = remember(attempts) { attempts.filter { isToday(it.ts) } }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                title = { Text("Прописи Бори", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Настройки") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
        bottomBar = { PravkaBottomBar(Tab.WORDS, onTab) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (todayAttempts.isNotEmpty()) {
                item {
                    PravkaCard {
                        Text("Сегодня", style = MaterialTheme.typography.labelMedium, color = PravkaColors.Muted)
                        Text(
                            "${wordsWord(todayAttempts.size)} · ${lettersWord(todayAttempts.sumOf { it.letters })}",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            }

            item {
                PravkaCard {
                    Text("Новые слова", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    if (settings.apiKey.isBlank()) {
                        Text(
                            "Чтобы разбирать фото, вставь API-ключ Anthropic в настройках (шестерёнка сверху).",
                            style = MaterialTheme.typography.bodySmall, color = PravkaColors.RuText,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    BigButton(
                        "Сфотографировать страницу", onClick = { openCamera() },
                        icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            "Из галереи",
                            onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton("Ввести вручную", onClick = { showManual = true }, modifier = Modifier.weight(1f))
                    }

                    if (pending.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = PravkaColors.Grid)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Выбрано фото: ${pending.size}. Можно добавить ещё страницу или отправить на разбор.",
                            style = MaterialTheme.typography.bodyMedium, color = PravkaColors.Ink2,
                        )
                        Spacer(Modifier.height(10.dp))
                        BigButton(
                            "Распознать слова (Opus)",
                            onClick = { vm.parsePhotos(pending.toList()) },
                            container = PravkaColors.En,
                            enabled = parse !is ParseState.Running,
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { pending.clear() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Сбросить фото", color = PravkaColors.Muted)
                        }
                    }
                }
            }

            item { SectionTitle("Списки слов") }

            if (lists.isEmpty()) {
                item { EmptyHint("Списков пока нет. Сфотографируй страницу словаря или введи слова вручную.") }
            }

            items(lists, key = { it.id }) { list ->
                val doneToday = attempts.count { it.listId == list.id && isToday(it.ts) }
                val total = list.itemCount * 2
                ListRow(
                    list = list,
                    doneToday = doneToday,
                    total = total,
                    onClick = { onOpenList(list.id) },
                    onDelete = { deleteTarget = list },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // Диалоги и состояния
    if (showManual) {
        ManualListDialog(
            onCreate = { title, text ->
                showManual = false
                vm.createManualList(title, text) { onOpenList(it.id) }
            },
            onDismiss = { showManual = false },
        )
    }
    deleteTarget?.let { t ->
        ConfirmDialog(
            title = "Удалить список?",
            text = "«${t.title}» будет удалён. Результаты Бори по этим словам останутся в статистике.",
            onConfirm = { vm.deleteList(t.id) },
            onDismiss = { deleteTarget = null },
        )
    }
    when (val p = parse) {
        is ParseState.Running -> {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Opus читает страницу") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(color = PravkaColors.En)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (p.photos > 1) "Разбираю ${p.photos} фото, это обычно 20–40 секунд." else "Обычно это 15–30 секунд.",
                            style = MaterialTheme.typography.bodyMedium, color = PravkaColors.Ink2,
                        )
                    }
                },
            )
        }
        is ParseState.Error -> {
            AlertDialog(
                onDismissRequest = { vm.parseHandled() },
                title = { Text("Не получилось") },
                text = { Text(p.message) },
                confirmButton = { TextButton(onClick = { vm.parseHandled() }) { Text("Понятно") } },
            )
        }
        else -> Unit
    }
}

@Composable
private fun ListRow(list: WordListWithCount, doneToday: Int, total: Int, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = PravkaColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, PravkaColors.Border),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(list.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${wordsWord(list.itemCount)} · ${fmtDate(list.createdAt)}",
                    style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                )
            }
            if (doneToday > 0) {
                val allDone = doneToday >= total
                Pill(
                    if (allDone) "готово" else "$doneToday/$total",
                    bg = if (allDone) PravkaColors.GoodSoft else PravkaColors.Surface2,
                    fg = if (allDone) PravkaColors.GoodText else PravkaColors.Ink2,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = PravkaColors.Muted)
            }
        }
    }
}
