// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import helium314.keyboard.settings.SearchSettingsScreen

// Pravka settings inside the keyboard: the API key and a short manual -
// mirrors the Pravka app's settings tab.
@Composable
fun PravkaScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("pravka", Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(prefs.getString("pravka_api_key", "").orEmpty()) }
    var saved by remember { mutableStateOf(false) }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Правка",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                Text("API-ключ Anthropic", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Ключ хранится только на этом устройстве и уходит только на api.anthropic.com.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; saved = false },
                    singleLine = true,
                    label = { Text("sk-ant-…") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = key.isBlank() || key.trim().startsWith("sk-ant-"),
                    onClick = {
                        prefs.edit().putString("pravka_api_key", key.trim()).apply()
                        saved = true
                    },
                ) {
                    Text(if (saved) "Сохранено" else "Сохранить")
                }
                Spacer(Modifier.height(24.dp))
                Text("Как пользоваться", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Кнопки Правки живут на панели инструментов над клавишами:\n\n" +
                        "• Микрофон — диктовка: клавиши накрывает плашка с живым " +
                        "текстом; тап по плашке или по микрофону — стоп, текст " +
                        "чистится и встаёт у курсора (плюс копия в буфере).\n\n" +
                        "• «П» — почистить весь текст поля.\n\n" +
                        "• «Причесать стиль», «Короче», «Длиннее» — переделка всего " +
                        "поля на усиленной модели.\n\n" +
                        "Голосовые команды в диктовке: «с новой строки», «абзац». " +
                        "Набор кнопок и их порядок настраиваются в разделе " +
                        "«Панель инструментов».",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
