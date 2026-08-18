// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import helium314.keyboard.pravka.PravkaStore
import helium314.keyboard.settings.SearchSettingsScreen
import java.util.Locale
import org.json.JSONObject

// The Pravka app's transcripts tab: full text of every fix/dictation, newest
// first - tap a card to copy its result. Errors carry the raw take, so a
// "куда делась диктовка" always has an answer here.
@Composable
fun PravkaHistoryScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val entries = remember { PravkaStore.readHistory(ctx, 300) }
    var expanded by remember { mutableIntStateOf(-1) }
    var showEvents by remember { mutableStateOf(false) }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Расшифровки",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Каждая диктовка и правка, новые сверху. Тап по карточке — " +
                            "развернуть и скопировать; при ошибке исходник тоже здесь.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val raw = PravkaStore.historyRawText(ctx)
                            if (raw.isBlank()) toast(ctx, "История пуста")
                            else PravkaStore.shareTextFile(ctx, "pravka-history.jsonl", "application/json", raw)
                        }) { Text("Выгрузить JSONL") }
                        OutlinedButton(onClick = { showEvents = !showEvents }) {
                            Text(if (showEvents) "Скрыть журнал" else "Журнал распознавания")
                        }
                    }
                }
                if (showEvents) {
                    item {
                        val events = remember { PravkaStore.readEvents(ctx, 300) }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    if (events.isEmpty()) "Журнал распознавания пуст (появится после первой диктовки)."
                                    else events.joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                )
                                Row {
                                    TextButton(onClick = { copyText(ctx, events.joinToString("\n")) }) {
                                        Text("Копировать")
                                    }
                                    TextButton(onClick = {
                                        val raw = PravkaStore.eventsRawText(ctx)
                                        if (raw.isBlank()) toast(ctx, "Журнал пуст")
                                        else PravkaStore.shareTextFile(ctx, "pravka-events.log", "text/plain", raw)
                                    }) { Text("Выгрузить файлом") }
                                }
                            }
                        }
                    }
                }
                if (entries.isEmpty()) {
                    item {
                        Text("Пока пусто — здесь появится каждая диктовка и правка.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                itemsIndexed(entries) { i, e ->
                    TranscriptCard(
                        entry = e,
                        expanded = expanded == i,
                        onClick = {
                            val out = e.optString("output").ifBlank { e.optString("input") }
                            if (expanded == i) {
                                if (out.isNotBlank()) copyText(ctx, out)
                            } else expanded = i
                        },
                        ctx = ctx,
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptCard(entry: JSONObject, expanded: Boolean, onClick: () -> Unit, ctx: Context) {
    val ruLoc = remember { Locale.forLanguageTag("ru") }
    val error = entry.optString("error").takeIf { entry.has("error") }
    val input = entry.optString("input")
    val output = entry.optString("output")
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            val meta = buildString {
                append(
                    when (entry.optString("source")) {
                        "fab" -> "кнопка"
                        "assist" -> "ИИ"
                        else -> "клавиатура"
                    }
                )
                entry.optString("model").let {
                    if (it.contains("opus")) append(" · opus") else if (it.contains("sonnet")) append(" · sonnet")
                }
                val lat = entry.optLong("latency_ms")
                if (lat > 0) append(" · ").append(String.format(ruLoc, "%.1f с", lat / 1000.0))
                append(" · ").append(input.length).append(" симв.")
                val cost = entry.optDouble("cost_usd", 0.0)
                if (cost > 0) append(" · $").append(String.format(Locale.US, "%.4f", cost))
                if (error == null && !entry.optBoolean("changed")) append(" · без изменений")
            }
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                entry.optString("ts").replace('T', ' ').take(16),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            if (!expanded) {
                Text(
                    (output.ifBlank { input }).take(200),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                )
            } else {
                if (output.isNotBlank() && output != input) {
                    Text("Результат", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(output, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                Text("Исходник", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(input, style = MaterialTheme.typography.bodyMedium)
                Row {
                    if (output.isNotBlank()) {
                        TextButton(onClick = { copyText(ctx, output) }) { Text("Копировать результат") }
                    }
                    TextButton(onClick = { copyText(ctx, input) }) { Text("Копировать исходник") }
                }
            }
        }
    }
}

private fun copyText(ctx: Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Правка", text))
        toast(ctx, "Скопировано")
    }
}

private fun toast(ctx: Context, msg: String) =
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
