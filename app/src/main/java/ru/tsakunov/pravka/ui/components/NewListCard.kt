package ru.tsakunov.pravka.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import ru.tsakunov.pravka.ui.vm.ParseState

/** Карточка «Новый урок»: фото словаря (камера или галерея) или ручной ввод. */
@Composable
fun NewListCard(vm: AppViewModel, onCreated: (String) -> Unit) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val parse by vm.parse.collectAsStateWithLifecycle()
    val picker = rememberPhotoPicker(onError = { vm.showToast(it) })
    var showManual by remember { mutableStateOf(false) }

    LaunchedEffect(parse) {
        val p = parse
        if (p is ParseState.Done) {
            picker.clear()
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
        PhotoPickerControls(
            picker = picker,
            cameraLabel = "Сфотографировать словарь",
            submitLabel = "Распознать слова (Opus)",
            submitting = parse is ParseState.Running,
            onSubmit = { vm.parsePhotos(picker.pending) },
            extraButton = { SecondaryButton("Ввести вручную", onClick = { showManual = true }, modifier = Modifier.weight(1f)) },
        )
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
        is ParseState.Running -> WorkingDialog("Opus читает страницу", p.photos)
        is ParseState.Error -> AlertDialog(
            onDismissRequest = { vm.parseHandled() },
            title = { Text("Не получилось") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = { vm.parseHandled() }) { Text("Понятно") } },
        )
        else -> Unit
    }
}
