// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import helium314.keyboard.pravka.PravkaStore
import helium314.keyboard.pravka.PravkaStore.DictEntry
import helium314.keyboard.pravka.PravkaStore.DictMode
import helium314.keyboard.settings.SearchSettingsScreen

// The personal dictionary: HARD replaces before the request, HINT instructs
// the model, PROTECT forbids changing a spelling. All entries also bias the
// speech recognizer toward these words.
@Composable
fun PravkaDictionaryScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf(PravkaStore.dictionary(ctx)) }
    var editing by remember { mutableStateOf<DictEntry?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    fun save(newEntries: List<DictEntry>) {
        entries = newEntries
        PravkaStore.saveDictionary(ctx, newEntries)
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Словарь Правки",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            LazyColumn(Modifier.padding(innerPadding).padding(horizontal = 16.dp)) {
                item {
                    Text(
                        "Замена — правится до отправки; подсказка — модель пишет " +
                            "«как» вместо «что услышала»; защита — слово не трогать. " +
                            "Все слова подсказываются и распознавателю речи.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Button(onClick = { showAdd = true }) { Text("Добавить слово") }
                    Spacer(Modifier.height(8.dp))
                }
                items(entries, key = { it.id }) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (e.mode == DictMode.PROTECT) e.from else "${e.from} → ${e.to}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    when (e.mode) {
                                        DictMode.HARD -> "замена"
                                        DictMode.HINT -> "подсказка"
                                        DictMode.PROTECT -> "защита"
                                    } + if (e.note.isNotBlank()) " · ${e.note}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = e.enabled,
                                onCheckedChange = { on ->
                                    save(entries.map { if (it.id == e.id) it.copy(enabled = on) else it })
                                },
                            )
                        }
                        Row {
                            TextButton(onClick = { editing = e }) { Text("Изменить") }
                            TextButton(onClick = { save(entries.filter { it.id != e.id }) }) { Text("Удалить") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAdd) {
        EntryDialog(
            title = "Новое слово",
            initial = null,
            onDismiss = { showAdd = false },
            onConfirm = { from, to, mode, note ->
                showAdd = false
                PravkaStore.addEntry(ctx, from, to, mode, note)
                entries = PravkaStore.dictionary(ctx)
            },
        )
    }
    editing?.let { e ->
        EntryDialog(
            title = "Изменить слово",
            initial = e,
            onDismiss = { editing = null },
            onConfirm = { from, to, mode, note ->
                editing = null
                save(entries.map {
                    if (it.id == e.id) it.copy(from = from.trim(), to = to.trim(), mode = mode, note = note.trim())
                    else it
                })
            },
        )
    }
}

@Composable
private fun ModeOption(label: String, value: DictMode, current: DictMode, onSelect: (DictMode) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onSelect(value) },
    ) {
        RadioButton(selected = current == value, onClick = { onSelect(value) })
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EntryDialog(
    title: String,
    initial: DictEntry?,
    onDismiss: () -> Unit,
    onConfirm: (from: String, to: String, mode: DictMode, note: String) -> Unit,
) {
    var from by remember { mutableStateOf(initial?.from.orEmpty()) }
    var to by remember { mutableStateOf(initial?.to.orEmpty()) }
    var mode by remember { mutableStateOf(initial?.mode ?: DictMode.HINT) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = from, onValueChange = { from = it }, singleLine = true,
                    label = { Text(if (mode == DictMode.PROTECT) "Слово" else "Как слышится / пишется сейчас") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (mode != DictMode.PROTECT) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = to, onValueChange = { to = it }, singleLine = true,
                        label = { Text("Как должно быть") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                ModeOption("Подсказка — модель напишет «как» вместо «что услышала»", DictMode.HINT, mode) { mode = it }
                ModeOption("Замена — правится ещё до отправки", DictMode.HARD, mode) { mode = it }
                ModeOption("Защита — слово не изменять", DictMode.PROTECT, mode) { mode = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, singleLine = true,
                    label = { Text("Заметка (необязательно)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = from.isNotBlank() && (mode == DictMode.PROTECT || to.isNotBlank()),
                onClick = { onConfirm(from, to, mode, note) },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
