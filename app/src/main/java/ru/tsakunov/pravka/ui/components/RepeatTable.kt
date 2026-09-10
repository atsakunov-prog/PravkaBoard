package ru.tsakunov.pravka.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.domain.RepeatRow
import ru.tsakunov.pravka.domain.RepeatSummary
import ru.tsakunov.pravka.ui.fmtTime
import ru.tsakunov.pravka.ui.lettersWord
import ru.tsakunov.pravka.ui.theme.PravkaColors
import ru.tsakunov.pravka.ui.wordsWord
import kotlin.math.abs

private val timeCol = 52.dp
private val deltaCol = 58.dp
private val cellStyle = TextStyle(fontSize = 14.sp, fontFeatureSettings = "tnum", color = PravkaColors.Ink)
private val headStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = PravkaColors.Muted, letterSpacing = 0.4.sp)

/** Таблица «слово | 1-й | 2-й | 3-й | Δ»: сколько времени ушло на то же слово в каждый проход. */
@Composable
fun RepeatTable(rows: List<RepeatRow>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("СЛОВО", style = headStyle, modifier = Modifier.weight(1f))
            HeadCell("1-й"); HeadCell("2-й"); HeadCell("3-й")
            Text("Δ", style = headStyle, textAlign = TextAlign.End, modifier = Modifier.width(deltaCol))
        }
        HorizontalDivider(color = PravkaColors.Grid)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LangTag(r.lang)
                    Text(
                        r.word ?: "${lettersWord(r.letters)} · с бумаги",
                        style = if (r.word != null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                        color = if (r.word != null) PravkaColors.Ink else PravkaColors.Ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                TimeCell(r.passes.getOrNull(0), false)
                TimeCell(r.passes.getOrNull(1), r.passes.size == 2)
                // Третья колонка показывает последний проход, если их три и больше.
                if (r.passes.size >= 3) TimeCell(r.last, true, extra = if (r.passes.size > 3) "×${r.passes.size}" else null)
                else TimeCell(null, false)
                DeltaCell(r.deltaPct)
            }
            HorizontalDivider(color = PravkaColors.Grid)
        }
    }
}

@Composable
private fun HeadCell(text: String) {
    Text(text, style = headStyle, textAlign = TextAlign.End, modifier = Modifier.width(timeCol))
}

@Composable
private fun TimeCell(a: Attempt?, bold: Boolean, extra: String? = null) {
    Column(Modifier.width(timeCol), horizontalAlignment = Alignment.End) {
        Text(
            if (a == null) "—" else fmtTime(a.ms),
            style = cellStyle.copy(
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                color = if (a == null) PravkaColors.Grid else PravkaColors.Ink,
            ),
        )
        if (extra != null) Text(extra, style = TextStyle(fontSize = 10.sp, color = PravkaColors.Muted))
    }
}

@Composable
private fun DeltaCell(pct: Int) {
    val (text, color) = when {
        pct <= -3 -> "−${abs(pct)}%" to PravkaColors.GoodText
        pct >= 3 -> "+$pct%" to PravkaColors.Muted
        else -> "≈" to PravkaColors.Muted
    }
    Text(
        text,
        style = cellStyle.copy(fontWeight = FontWeight.Bold, color = color),
        textAlign = TextAlign.End,
        modifier = Modifier.width(deltaCol),
    )
}

/** Фраза-итог для Бори: «Второй раз то же слово ты обычно пишешь на 19% быстрее (9 из 11 слов)». */
@Composable
fun RepeatSummaryText(s: RepeatSummary, modifier: Modifier = Modifier) {
    val pct = s.medianSecondPassPct
    val text = when {
        pct <= -3 -> "Второй раз то же слово Боря обычно пишет на ${abs(pct)}% быстрее: ${s.fasterCount} из ${wordsWord(s.words)} стали быстрее."
        pct >= 3 -> "Второй раз пока обычно выходит медленнее на $pct%: быстрее стали ${s.fasterCount} из ${wordsWord(s.words)}."
        else -> "Второй проход идёт примерно с той же скоростью, что и первый (${wordsWord(s.words)})."
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (pct <= -3) PravkaColors.GoodText else PravkaColors.Ink2,
        modifier = modifier,
    )
}

@Suppress("unused")
private val transparent = Color.Transparent
