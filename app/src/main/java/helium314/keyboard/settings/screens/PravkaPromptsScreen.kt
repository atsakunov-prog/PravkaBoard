// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.pravka.PravkaPromptStore
import helium314.keyboard.pravka.PravkaPromptStore.PromptId
import helium314.keyboard.pravka.PravkaPrompts
import helium314.keyboard.settings.SearchSettingsScreen

// The Pravka app's prompts tab: every prompt is editable on the device,
// factory text stays as the reset target. The master CLEAN prompt keeps its
// placeholders validated ({INPUT} required, {DICT} warned about).
@Composable
fun PravkaPromptsScreen(onClickBack: () -> Unit) {
    var editing by remember { mutableStateOf<PromptId?>(null) }
    val current = editing
    SearchSettingsScreen(
        onClickBack = { if (editing != null) editing = null else onClickBack() },
        title = "Промпты",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.padding(innerPadding)) {
                if (current == null) PromptList(onOpen = { editing = it })
                else PromptEditor(current, onBack = { editing = null })
            }
        }
    }
}

@Composable
private fun PromptList(onOpen: (PromptId) -> Unit) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Тексты запросов к модели. Правки сохраняются на устройстве; " +
                "«Сбросить» возвращает заводской текст.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (id in PromptId.entries) {
            val override = PravkaPromptStore.override(ctx, id)
            val effective = override ?: PravkaPromptStore.factory(id)
            Card(onClick = { onOpen(id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            id.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (override != null) {
                            Text(
                                "изменён",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        effective.lineSequence().take(2).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${effective.length} симв. (≈${effective.length / 3} токенов)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptEditor(id: PromptId, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var savedMark by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        text = PravkaPromptStore.effective(ctx, id)
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text(
                id.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null; savedMark = false },
            enabled = loaded,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp),
        )
        Text(
            "${text.length} симв. (≈${text.length / 3} токенов)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        warning?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                // Placeholders live only in the master CLEAN prompt; the redo
                // and assist prompts are free-form texts.
                if (id == PromptId.CLEAN) {
                    if (!text.contains(PravkaPrompts.PLACEHOLDER_INPUT)) {
                        error = "В промпте нет ${PravkaPrompts.PLACEHOLDER_INPUT} — без него текст не попадёт в запрос."
                        return@Button
                    }
                    warning = if (!text.contains(PravkaPrompts.PLACEHOLDER_DICT))
                        "Нет ${PravkaPrompts.PLACEHOLDER_DICT} — подсказки словаря не попадут в промпт."
                    else null
                }
                PravkaPromptStore.setOverride(ctx, id, text)
                savedMark = true
            }) {
                Text(if (savedMark) "Сохранено" else "Сохранить")
            }
            OutlinedButton(onClick = {
                if (confirmReset) {
                    PravkaPromptStore.resetToFactory(ctx, id)
                    text = PravkaPromptStore.factory(id)
                    confirmReset = false
                    savedMark = false
                    error = null
                    warning = null
                } else {
                    confirmReset = true
                }
            }) {
                Text(if (confirmReset) "Точно сбросить?" else "Сбросить")
            }
        }
    }
}
