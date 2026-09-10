package ru.tsakunov.pravka.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.tsakunov.pravka.domain.DayBucket
import ru.tsakunov.pravka.domain.StudyKind
import ru.tsakunov.pravka.ui.theme.PravkaColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** Цвет занятия на графиках времени: тот же язык, что и вкладки. */
fun StudyKind.color(): Color = when (this) {
    StudyKind.WRITING -> PravkaColors.En
    StudyKind.WORDS -> PravkaColors.Violet
    StudyKind.GRAMMAR -> PravkaColors.Gold
    StudyKind.READING -> PravkaColors.Good
}

private val dayLabel = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru-RU"))

/** Минуты занятий по дням, столбики сложены по видам; сегодняшний день подписан. */
@Composable
fun MinutesChart(days: List<DayBucket>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = PravkaColors.Muted)
    val today = LocalDate.now()
    Column(modifier) {
        if (days.all { it.total == 0L }) {
            Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                Text("За этот период занятий не было", color = PravkaColors.Muted)
            }
        } else {
            val maxMin = max(1.0, ceil(days.maxOf { it.total } / 60_000.0))
            val (step, top) = niceStep(maxMin)
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                val leftPad = 30.dp.toPx()
                val bottomPad = 18.dp.toPx()
                val topPad = 6.dp.toPx()
                val plotW = size.width - leftPad
                val plotH = size.height - bottomPad - topPad
                fun y(min: Double) = topPad + plotH - (min / top * plotH).toFloat()

                // Сетка и подписи оси Y
                var v = 0.0
                while (v <= top + 1e-9) {
                    val yy = y(v)
                    drawLine(PravkaColors.Grid, Offset(leftPad, yy), Offset(size.width, yy), strokeWidth = 1f, pathEffect = if (v == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    val t = measurer.measure(if (v == 0.0) "0" else v.toInt().toString(), labelStyle)
                    drawText(t, topLeft = Offset(leftPad - t.size.width - 6.dp.toPx(), yy - t.size.height / 2))
                    v += step
                }

                // Столбики
                val n = days.size
                val slot = plotW / n
                val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())
                days.forEachIndexed { i, d ->
                    val x = leftPad + slot * i + (slot - barW) / 2
                    var acc = 0.0
                    for (k in StudyKind.entries) {
                        val m = (d.ms[k] ?: 0L) / 60_000.0
                        if (m <= 0) continue
                        val yTop = y(acc + m)
                        val yBottom = y(acc)
                        drawRect(k.color(), topLeft = Offset(x, yTop), size = Size(barW, yBottom - yTop))
                        acc += m
                    }
                    if (d.day == today) {
                        drawRect(PravkaColors.Ink, topLeft = Offset(x - 2.dp.toPx(), y(0.0) + 3.dp.toPx()), size = Size(barW + 4.dp.toPx(), 2.dp.toPx()))
                    }
                    // Подписи дат: первая, последняя и каждая k-я, чтобы не слипались
                    val every = if (n <= 7) 1 else if (n <= 14) 2 else 5
                    if (i == n - 1 || i % every == 0 && i < n - 2) {
                        val t = measurer.measure(if (d.day == today) "сегодня" else d.day.format(dayLabel), labelStyle)
                        drawText(t, topLeft = Offset((x + barW / 2 - t.size.width / 2).coerceIn(leftPad, size.width - t.size.width), size.height - t.size.height))
                    }
                }
                // Ось
                drawLine(PravkaColors.Axis, Offset(leftPad, topPad), Offset(leftPad, y(0.0)), strokeWidth = 1f)
            }
            Text("минуты в день", style = MaterialTheme.typography.labelSmall, color = PravkaColors.Muted, modifier = Modifier.padding(start = 30.dp, top = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StudyKind.entries.forEach { k ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(10.dp).background(k.color(), CircleShape))
                    Text(k.label, style = MaterialTheme.typography.labelSmall, color = PravkaColors.Ink2)
                }
            }
        }
    }
}

/** Шаг сетки и верх шкалы в минутах: 1, 2, 5, 10, 15, 30, 60… */
private fun niceStep(maxMin: Double): Pair<Double, Double> {
    val candidates = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 240.0)
    val step = candidates.firstOrNull { maxMin / it <= 4 } ?: 480.0
    val top = ceil(maxMin / step) * step
    return step to max(top, step)
}
