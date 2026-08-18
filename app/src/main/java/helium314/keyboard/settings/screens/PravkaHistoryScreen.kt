// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import helium314.keyboard.pravka.PravkaStore
import helium314.keyboard.settings.SearchSettingsScreen
import org.json.JSONObject

// The fix journal, browsable: every dictation and fix with its input, output,
// error and cost - the owner's "why did this take disappear" first stop.
@Composable
fun PravkaHistoryScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val entries = remember { PravkaStore.readHistory(ctx, 500) }
    val totalCost = remember { entries.sumOf { it.optDouble("cost_usd", 0.0) } }
    val errorCount = remember { entries.count { it.has("error") } }
    var expanded by remember { mutableIntStateOf(-1) }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "История Правки",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            LazyColumn(Modifier.padding(innerPadding).padding(horizontal = 16.dp)) {
                item {
                    Text(
                        if (entries.isEmpty()) "Журнал пуст — здесь появится каждая диктовка и правка."
                        else "Показаны последние ${entries.size}, из них с ошибкой: $errorCount. " +
                            "Стоимость показанных: ≈ $" + "%.2f".format(totalCost),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                itemsIndexed(entries) { i, e ->
                    HistoryRow(
                        entry = e,
                        expanded = expanded == i,
                        onClick = { expanded = if (expanded == i) -1 else i },
                        ctx = ctx,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: JSONObject, expanded: Boolean, onClick: () -> Unit, ctx: Context) {
    val error = entry.optString("error").takeIf { entry.has("error") }
    val input = entry.optString("input")
    val output = entry.optString("output")
    val ts = entry.optString("ts").take(16).replace('T', ' ')
    val source = when (entry.optString("source")) {
        "fab" -> "кнопка"
        "assist" -> "ИИ"
        else -> "клавиатура"
    }
    val model = entry.optString("model").let {
        when {
            it.contains("opus") -> "· opus"
            it.contains("sonnet") -> "· sonnet"
            else -> ""
        }
    }
    val status = when {
        error != null -> "✗ ошибка"
        entry.optBoolean("changed") -> "✓"
        else -> "="
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text("$ts · $source $model", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        if (!expanded) {
            Text(
                (error ?: output.ifBlank { input }).take(160),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
            )
        } else {
            if (error != null) {
                Text("Ошибка: $error", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            Text("Исходник", style = MaterialTheme.typography.labelMedium)
            Text(input, style = MaterialTheme.typography.bodyMedium)
            if (output.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Результат", style = MaterialTheme.typography.labelMedium)
                Text(output, style = MaterialTheme.typography.bodyMedium)
            }
            Row {
                TextButton(onClick = { copy(ctx, output.ifBlank { input }) }) {
                    Text(if (output.isNotBlank()) "Копировать результат" else "Копировать исходник")
                }
                if (output.isNotBlank() && input.isNotBlank()) {
                    TextButton(onClick = { copy(ctx, input) }) { Text("Копировать исходник") }
                }
            }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Правка", text))
        android.widget.Toast.makeText(ctx, "Скопировано", android.widget.Toast.LENGTH_SHORT).show()
    }
}
