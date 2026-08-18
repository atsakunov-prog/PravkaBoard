// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.pravka.GoogleSpeechSession
import helium314.keyboard.pravka.PravkaStore
import helium314.keyboard.pravka.fab.FabService
import helium314.keyboard.settings.SearchSettingsScreen
import java.util.Locale

// Pravka settings inside the keyboard, in the Pravka app's own layout
// language: brand header, service banner, orange section labels over cards.
@Composable
fun PravkaScreen(
    onClickBack: () -> Unit,
    onClickHistory: () -> Unit = {},
    onClickDictionary: () -> Unit = {},
    onClickPrompts: () -> Unit = {},
    onClickStats: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("pravka", Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(prefs.getString("pravka_api_key", "").orEmpty()) }
    var keyVisible by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val serviceEnabled = remember { FabService.instance != null }

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
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Brand header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "П",
                            fontSize = 26.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Правка", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "PravkaBoard ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onClickHistory) { Text("Расшифровки") }
                        val dictCount = remember { PravkaStore.dictionary(ctx).size }
                        Button(onClick = onClickDictionary) { Text("Словарь ($dictCount)") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onClickPrompts) { Text("Промпты") }
                        Button(onClick = onClickStats) { Text("Статистика") }
                    }
                }

                // Accessibility service status (the floating button)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (serviceEnabled) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (serviceEnabled) "Служба доступности включена — плавающая кнопка работает"
                            else "Служба доступности выключена — плавающая кнопка не работает",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (serviceEnabled) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer,
                        )
                        if (!serviceEnabled) {
                            Button(onClick = {
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }) { Text("Включить службу") }
                            HintText(
                                "Настройки → Спец. возможности → Правка — плавающая кнопка → " +
                                    "Включить. Кнопка «П» поверх любых приложений: короткое " +
                                    "нажатие — диктовка, долгое — правка поля, перетаскивание — перенос."
                            )
                        }
                    }
                }

                SectionCard(label = "Ключ Anthropic API") {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it; saved = false },
                        singleLine = true,
                        visualTransformation = if (keyVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        label = { Text("sk-ant-…") },
                        trailingIcon = {
                            TextButton(onClick = { keyVisible = !keyVisible }) {
                                Text(if (keyVisible) "скрыть" else "показать")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    HintText("Хранится только на этом устройстве. Создай отдельный ключ с лимитом трат на console.anthropic.com.")
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = key.isBlank() || key.trim().startsWith("sk-ant-"),
                        onClick = {
                            prefs.edit().putString("pravka_api_key", key.trim()).apply()
                            saved = true
                        },
                    ) {
                        Text(if (saved) "Сохранено" else "Сохранить")
                    }
                }

                SectionCard(label = "Плавающая кнопка") {
                    var sizeSlider by remember { mutableFloatStateOf(prefs.getInt("fab_size", 48).toFloat()) }
                    var alphaSlider by remember { mutableFloatStateOf(prefs.getFloat("fab_alpha", 0.35f)) }
                    Text("Размер: ${sizeSlider.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = sizeSlider,
                        onValueChange = { sizeSlider = it },
                        onValueChangeFinished = {
                            prefs.edit().putInt("fab_size", sizeSlider.toInt()).apply()
                            FabService.instance?.applyLook()
                        },
                        valueRange = 36f..72f,
                    )
                    Text(
                        "Прозрачность в покое: ${(alphaSlider * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = alphaSlider,
                        onValueChange = { alphaSlider = it },
                        onValueChangeFinished = {
                            prefs.edit().putFloat("fab_alpha", alphaSlider).apply()
                            FabService.instance?.applyLook()
                        },
                        valueRange = 0.15f..1f,
                    )
                }

                SectionCard(label = "Распознавание речи") {
                    var status by remember {
                        mutableStateOf(
                            if (GoogleSpeechSession.isAvailable(ctx)) "Google: распознавание готово"
                            else "Google: распознавание недоступно"
                        )
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            GoogleSpeechSession.triggerModelDownload(ctx)
                            android.widget.Toast.makeText(ctx, "Запросил подготовку офлайн-модели", android.widget.Toast.LENGTH_LONG).show()
                        }) { Text("Подготовить офлайн") }
                        OutlinedButton(onClick = {
                            status = if (GoogleSpeechSession.isAvailable(ctx)) "Google: распознавание готово"
                                else "Google: распознавание недоступно"
                        }) { Text("Обновить") }
                    }
                    Spacer(Modifier.height(6.dp))
                    HintText(
                        "Распознаёт Google (на устройстве, без интернета). Словарь подсказывает " +
                            "распознавателю твои имена и термины — чем полнее словарь, тем точнее."
                    )
                }

                SectionCard(label = "Как пользоваться") {
                    Text(
                        "«П» на панели инструментов — хаб: чистка, стиль, короче/длиннее, " +
                            "диктовка, ИИ-действия и история.\n\n" +
                            "Микрофон у пробела (долгое нажатие) — мгновенная диктовка: " +
                            "клавиши накрывает плашка с живым текстом, «Закончить» — вставить, " +
                            "«Отмена» — выбросить.\n\n" +
                            "Если в поле выделен фрагмент — Правка работает только с ним.\n\n" +
                            "Голосовые команды: «с новой строки», «абзац».",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SectionCard(label = "Перенос из приложения Правка") {
                    HintText(
                        "В Правке: «Словарь → Экспорт JSON» и «Статистика → Выгрузить историю " +
                            "(JSONL)», сохрани файлы, потом импортируй их здесь."
                    )
                    Spacer(Modifier.height(8.dp))
                    val dictImport = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri == null) return@rememberLauncherForActivityResult
                        val text = runCatching {
                            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                        }.getOrDefault("")
                        PravkaStore.importDictionaryJson(ctx, text)
                            .onSuccess { n ->
                                android.widget.Toast.makeText(ctx, "Словарь: добавлено записей — $n", android.widget.Toast.LENGTH_LONG).show()
                            }
                            .onFailure {
                                android.widget.Toast.makeText(ctx, "Не похоже на экспорт словаря Правки", android.widget.Toast.LENGTH_LONG).show()
                            }
                    }
                    val historyImport = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri == null) return@rememberLauncherForActivityResult
                        val text = runCatching {
                            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                        }.getOrDefault("")
                        PravkaStore.importHistoryJsonl(ctx, text)
                            .onSuccess { n ->
                                android.widget.Toast.makeText(ctx, "История: перенесено записей — $n", android.widget.Toast.LENGTH_LONG).show()
                            }
                            .onFailure {
                                android.widget.Toast.makeText(ctx, "Не похоже на историю Правки (JSONL)", android.widget.Toast.LENGTH_LONG).show()
                            }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { dictImport.launch(arrayOf("*/*")) }) {
                            Text("Словарь (JSON)")
                        }
                        OutlinedButton(onClick = { historyImport.launch(arrayOf("*/*")) }) {
                            Text("История (JSONL)")
                        }
                    }
                }
            }
        }
    }
}

/** Small uppercase label in the accent color above a card - the app's style. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.forLanguageTag("ru")),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SectionCard(
    label: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (label != null) SectionLabel(label)
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
