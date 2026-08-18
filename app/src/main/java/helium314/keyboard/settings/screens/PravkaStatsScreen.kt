// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import helium314.keyboard.pravka.PravkaStore
import helium314.keyboard.settings.SearchSettingsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

// The Pravka app's statistics tab, computed straight from the history
// journal: spend by period, request counters, character/token volume.
@Composable
fun PravkaStatsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = remember { computeStats(PravkaStore.readHistory(ctx, 10_000)) }
    val ru = remember { Locale.forLanguageTag("ru") }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Статистика",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StatsCard(label = "Расходы") {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "$%.4f".format(Locale.US, s.costToday),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "сегодня",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    StatRow("За 7 дней", "$%.4f".format(Locale.US, s.costWeek))
                    StatRow("За 30 дней", "$%.4f".format(Locale.US, s.costMonth))
                    StatRow("За всё время", "$%.4f".format(Locale.US, s.costTotal))
                }

                StatsCard(label = "Запросы") {
                    StatRow("Всего", s.total.toString())
                    StatRow("С клавиатуры", s.keyboard.toString())
                    StatRow("С плавающей кнопки", s.fab.toString())
                    StatRow("ИИ-действия", s.assist.toString())
                    StatRow("Без изменений", s.unchanged.toString())
                    StatRow("Ошибки", s.errors.toString())
                    StatRow("Символов обработано", "%,d".format(ru, s.chars))
                    StatRow("Токены (вход / выход)", "%,d / %,d".format(ru, s.tokensIn, s.tokensOut))
                    if (s.avgLatencyMs > 0)
                        StatRow("Средняя задержка", String.format(ru, "%.1f с", s.avgLatencyMs / 1000.0))
                }

                Column {
                    OutlinedButton(
                        onClick = {
                            val raw = PravkaStore.historyRawText(ctx)
                            if (raw.isBlank()) {
                                android.widget.Toast.makeText(ctx, "История пуста", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                PravkaStore.shareTextFile(ctx, "pravka-history.jsonl", "application/json", raw)
                            }
                        },
                    ) { Text("Выгрузить историю (JSONL)") }
                    Text(
                        "Полный журнал правок в JSONL — тот же формат, что и в приложении Правка.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private class StatsSnapshot(
    val costToday: Double, val costWeek: Double, val costMonth: Double, val costTotal: Double,
    val total: Int, val keyboard: Int, val fab: Int, val assist: Int,
    val unchanged: Int, val errors: Int, val chars: Long,
    val tokensIn: Long, val tokensOut: Long, val avgLatencyMs: Double,
)

private fun computeStats(entries: List<JSONObject>): StatsSnapshot {
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val today = dayFmt.format(Date())
    val dayMs = 24L * 3600 * 1000
    val weekEdge = Date(System.currentTimeMillis() - 7 * dayMs)
    val monthEdge = Date(System.currentTimeMillis() - 30 * dayMs)

    var costToday = 0.0; var costWeek = 0.0; var costMonth = 0.0; var costTotal = 0.0
    var keyboard = 0; var fab = 0; var assist = 0
    var unchanged = 0; var errors = 0
    var chars = 0L; var tokensIn = 0L; var tokensOut = 0L
    var latencySum = 0L; var latencyCount = 0

    for (e in entries) {
        val cost = e.optDouble("cost_usd", 0.0)
        costTotal += cost
        val day = e.optString("ts").take(10)
        if (day == today) costToday += cost
        val date = runCatching { dayFmt.parse(day) }.getOrNull()
        if (date != null) {
            if (!date.before(weekEdge)) costWeek += cost
            if (!date.before(monthEdge)) costMonth += cost
        }
        when (e.optString("source")) {
            "fab" -> fab++
            "assist" -> assist++
            else -> keyboard++
        }
        if (e.has("error")) errors++
        else if (!e.optBoolean("changed")) unchanged++
        chars += e.optString("input").length
        tokensIn += e.optInt("input_tokens") + e.optInt("cache_read_tokens") + e.optInt("cache_write_tokens")
        tokensOut += e.optInt("output_tokens")
        val lat = e.optLong("latency_ms")
        if (lat > 0) { latencySum += lat; latencyCount++ }
    }
    return StatsSnapshot(
        costToday, costWeek, costMonth, costTotal,
        entries.size, keyboard, fab, assist, unchanged, errors,
        chars, tokensIn, tokensOut,
        if (latencyCount > 0) latencySum.toDouble() / latencyCount else 0.0,
    )
}

@Composable
private fun StatsCard(label: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label.uppercase(Locale.forLanguageTag("ru")),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
