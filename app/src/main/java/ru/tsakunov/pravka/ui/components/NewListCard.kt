package ru.tsakunov.pravka.ui.components

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.vm.ParseState
import java.io.File

/** Карточка «Новый урок»: фото словаря (камера или галерея) или ручной ввод. */
@Composable
fun NewListCard(vm: AppViewModel, onCreated: (String) -> Unit) {
    val context = LocalContext.current
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val parse by vm.parse.collectAsStateWithLifecycle()

    // Переживают уничтожение процесса, пока открыта камера (Uri — Parcelable).
    var pending by rememberSaveable(stateSaver = listSaver<List<Uri>, String>(save = { it.map(Uri::toString) }, restore = { it.map(Uri::parse) })) {
        mutableStateOf(emptyList())
    }
    var cameraTarget by rememberSaveable { mutableStateOf<Uri?>(null) }
    var showManual by remember { mutableStateOf(false) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = cameraTarget
        if (ok && target != null) pending = pending + target
        cameraTarget = null
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        pending = (pending + uris).distinct()
    }

    fun openCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "page_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraTarget = uri
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            cameraTarget = null
            vm.showToast("На телефоне нет приложения камеры, выбери фото из галереи")
        }
    }

    LaunchedEffect(parse) {
        val p = parse
        if (p is ParseState.Done) {
            pending = emptyList()
            vm.parseHandled()
            onCreated(p.list.id)
        }
    }

    PravkaCard {
        Text("Новый урок", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        if (settings.apiKey.isBlank()) {
            Text(
                "Чтобы разбирать фото, вставь API-ключ Anthropic в настройках (шестерёнка сверху).",
                style = MaterialTheme.typography.bodySmall, color = PravkaColors.RuText,
            )
            Spacer(Modifier.height(8.dp))
        }
        BigButton(
            "Сфотографировать словарь", onClick = { openCamera() },
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
            TextButton(onClick = { pending = emptyList() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Сбросить фото", color = PravkaColors.Muted)
            }
        }
    }

    if (showManual) {
        ManualListDialog(
            onCreate = { title, text ->
                showManual = false
                vm.createManualList(title, text) { onCreated(it.id) }
            },
            onDismiss = { showManual = false },
        )
    }
    when (val p = parse) {
        is ParseState.Running -> AlertDialog(
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
        is ParseState.Error -> AlertDialog(
            onDismissRequest = { vm.parseHandled() },
            title = { Text("Не получилось") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = { vm.parseHandled() }) { Text("Понятно") } },
        )
        else -> Unit
    }
}
