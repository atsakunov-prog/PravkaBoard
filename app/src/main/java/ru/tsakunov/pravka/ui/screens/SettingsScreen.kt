@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tsakunov.pravka.BuildConfig
import ru.tsakunov.pravka.ui.components.*
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.vm.AppViewModel
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var key by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var showKey by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = vm.exportJson()
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }
            vm.showToast("Резервная копия сохранена")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                }
                val n = vm.importJson(text)
                vm.showToast("Импортировано записей: $n")
            } catch (e: Exception) {
                vm.showToast("Не удалось импортировать: ${e.message ?: "неверный файл"}")
            }
        }
    }

    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                navigationIcon = { BackIcon(onBack) },
                title = { Text("Настройки", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PravkaCard {
                Text("Claude", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API-ключ Anthropic") },
                    placeholder = { Text("sk-ant-…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null)
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Модель") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("По умолчанию claude-opus-5") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
                Spacer(Modifier.height(8.dp))
                BigButton(
                    "Сохранить",
                    onClick = {
                        vm.setApiKey(key)
                        vm.setModel(model.ifBlank { "claude-opus-5" })
                        vm.showToast("Сохранено")
                    },
                    enabled = key != settings.apiKey || model != settings.model,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ключ хранится только на этом телефоне и отправляется только на api.anthropic.com.",
                    style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                )
            }

            PravkaCard {
                Text("Данные", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        "Сохранить копию",
                        onClick = { exportLauncher.launch("pravka-borya-${LocalDate.now()}.json") },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        "Восстановить",
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { confirmReset = true }) { Text("Сбросить все данные", color = PravkaColors.Danger) }
            }

            PravkaCard {
                Text("О приложении", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Прописи Бори ${BuildConfig.VERSION_NAME}", color = PravkaColors.Ink2)
                Text(
                    "Таймер письма по словам, статистика скорости по буквам для русского и английского, рекорды и конфетти. " +
                        "Рекордом считаются слова от 3 букв.",
                    style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Сбросить всё?",
            text = "Списки слов и все результаты будут удалены. Останется только бумажная статистика сентября 2026. Ключ API сохранится.",
            confirmText = "Сбросить",
            onConfirm = { vm.resetAll() },
            onDismiss = { confirmReset = false },
        )
    }
}
