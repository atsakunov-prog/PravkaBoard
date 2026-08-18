// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import helium314.keyboard.pravka.PravkaApi
import helium314.keyboard.pravka.PravkaStore
import helium314.keyboard.pravka.PravkaStore.DictEntry
import helium314.keyboard.pravka.PravkaStore.DictMode
import helium314.keyboard.settings.SearchSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Pravka app's dictionary tab, verbatim: sections by mode with colored
// dots, search, hit counters, the entry dialog, JSON export/import and the
// Sonnet miner that proposes entries from recurring recognition errors.
@Composable
fun PravkaDictionaryScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(PravkaStore.dictionary(ctx)) }
    var search by remember { mutableStateOf("") }
    var dialogEntry by remember { mutableStateOf<DictEntry?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var mining by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<PravkaApi.DictSuggestion>?>(null) }
    var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }

    fun save(newEntries: List<DictEntry>) {
        entries = newEntries
        PravkaStore.saveDictionary(ctx, newEntries)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        PravkaStore.importDictionaryJson(ctx, text)
            .onSuccess { n ->
                entries = PravkaStore.dictionary(ctx)
                toast(ctx, "Добавлено записей: $n")
            }
            .onFailure { toast(ctx, "Не похоже на экспорт словаря Правки") }
    }

    val query = search.trim().lowercase()
    fun section(mode: DictMode) = entries
        .filter { it.mode == mode }
        .filter { query.isEmpty() || it.from.lowercase().contains(query) || it.to.lowercase().contains(query) }
        .sortedByDescending { it.hits }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Словарь",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { showAddDialog = true }) { Text("Добавить") }
                    }
                    Row {
                        TextButton(onClick = {
                            if (!PravkaStore.shareTextFile(
                                    ctx, "pravka_dictionary.json", "application/json",
                                    PravkaStore.exportDictionaryJson(ctx),
                                )
                            ) toast(ctx, "Не получилось поделиться файлом")
                        }) { Text("Экспорт JSON") }
                        TextButton(onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        }) { Text("Импорт") }
                        TextButton(
                            enabled = !mining,
                            onClick = {
                                mining = true
                                scope.launch {
                                    val key = ctx.getSharedPreferences("pravka", Context.MODE_PRIVATE)
                                        .getString("pravka_api_key", "").orEmpty()
                                    val result = withContext(Dispatchers.IO) {
                                        PravkaApi.mineDictionary(key, PravkaStore.readPairs(ctx, 100))
                                    }
                                    mining = false
                                    result.onSuccess { found ->
                                        val known = PravkaStore.dictionary(ctx).map { it.from.lowercase() }.toHashSet()
                                        val fresh = found.filter { it.from.lowercase() !in known }
                                        if (fresh.isEmpty()) toast(ctx, "Повторяющихся ошибок не нашлось")
                                        else {
                                            suggestions = fresh
                                            picked = fresh.indices.toSet()
                                        }
                                    }.onFailure { toast(ctx, "Не получилось: ${it.message ?: ""}") }
                                }
                            },
                        ) { Text(if (mining) "Ищу…" else "Предложить из истории") }
                    }
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        singleLine = true,
                        label = { Text("Поиск") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }

                for (mode in DictMode.entries) {
                    val sectionEntries = section(mode)
                    item(key = "header_$mode") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(dictModeColor(mode), CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (mode) {
                                    DictMode.HARD -> "Автозамены (${sectionEntries.size})"
                                    DictMode.HINT -> "Подсказки модели (${sectionEntries.size})"
                                    DictMode.PROTECT -> "Защищённые слова (${sectionEntries.size})"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(sectionEntries, key = { it.id }) { entry ->
                        DictRow(
                            entry,
                            onClick = { dialogEntry = entry },
                            onToggle = { enabled ->
                                save(entries.map { if (it.id == entry.id) it.copy(enabled = enabled) else it })
                            },
                        )
                    }
                }
            }
        }
    }

    suggestions?.let { list ->
        AlertDialog(
            onDismissRequest = { suggestions = null },
            title = { Text("Предложения из истории") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.forEachIndexed { i, sug ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Checkbox(
                                checked = i in picked,
                                onCheckedChange = { on -> picked = if (on) picked + i else picked - i },
                            )
                            Column {
                                Text(
                                    if (sug.mode == DictMode.PROTECT) "Защита: ${sug.from}"
                                    else "${sug.from} → ${sug.to}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (sug.note.isNotBlank()) {
                                    Text(
                                        sug.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = list.filterIndexed { i, _ -> i in picked }
                    suggestions = null
                    for (sug in chosen) PravkaStore.addEntry(ctx, sug.from, sug.to, sug.mode, sug.note)
                    entries = PravkaStore.dictionary(ctx)
                    toast(ctx, "Добавлено: ${chosen.size}")
                }) { Text("Добавить выбранные") }
            },
            dismissButton = {
                TextButton(onClick = { suggestions = null }) { Text("Отмена") }
            },
        )
    }

    if (showAddDialog) {
        DictEntryDialog(
            entry = null,
            onDismiss = { showAddDialog = false },
            onSave = { from, to, mode, note, _ ->
                PravkaStore.addEntry(ctx, from, to, mode, note)
                entries = PravkaStore.dictionary(ctx)
                showAddDialog = false
            },
            onDelete = null,
        )
    }
    dialogEntry?.let { entry ->
        DictEntryDialog(
            entry = entry,
            onDismiss = { dialogEntry = null },
            onSave = { from, to, mode, note, enabled ->
                save(entries.map {
                    if (it.id == entry.id)
                        it.copy(from = from.trim(), to = to.trim(), mode = mode, note = note.trim(), enabled = enabled)
                    else it
                })
                dialogEntry = null
            },
            onDelete = {
                save(entries.filter { it.id != entry.id })
                dialogEntry = null
            },
        )
    }
}

private fun toast(ctx: Context, msg: String) =
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()

@Composable
private fun dictModeColor(mode: DictMode) = when (mode) {
    DictMode.HARD -> MaterialTheme.colorScheme.primary
    DictMode.HINT -> MaterialTheme.colorScheme.tertiary
    DictMode.PROTECT -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun DictRow(entry: DictEntry, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (entry.to.isNotBlank()) "${entry.from} → ${entry.to}" else entry.from,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (entry.note.isNotBlank()) {
                    Text(
                        entry.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.hits > 0) {
                Text(
                    "×${entry.hits}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Switch(checked = entry.enabled, onCheckedChange = onToggle, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun DictEntryDialog(
    entry: DictEntry?,
    onDismiss: () -> Unit,
    onSave: (from: String, to: String, mode: DictMode, note: String, enabled: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var from by remember { mutableStateOf(entry?.from ?: "") }
    var to by remember { mutableStateOf(entry?.to ?: "") }
    var mode by remember { mutableStateOf(entry?.mode ?: DictMode.HARD) }
    var note by remember { mutableStateOf(entry?.note ?: "") }
    var enabled by remember { mutableStateOf(entry?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) "Добавить" else "Изменить") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it },
                    label = { Text("Как слышится") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    singleLine = true,
                )
                if (mode != DictMode.PROTECT) {
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it },
                        label = { Text("Как должно быть") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        singleLine = true,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (m in DictMode.entries) {
                        RadioButton(selected = mode == m, onClick = { mode = m })
                        Text(
                            when (m) {
                                DictMode.HARD -> "замена"
                                DictMode.HINT -> "подсказка"
                                DictMode.PROTECT -> "защита"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (mode == DictMode.HINT) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Заметка для модели") },
                    )
                }
                if (entry != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                        Text("Включена", Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (from.isNotBlank()) onSave(from, to, mode, note, enabled) }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
