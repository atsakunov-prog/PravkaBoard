package ru.tsakunov.pravka.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Metric
import ru.tsakunov.pravka.domain.MIN_LETTERS_FOR_RECORD
import ru.tsakunov.pravka.domain.isToday
import ru.tsakunov.pravka.ui.fmtRateValue
import ru.tsakunov.pravka.ui.fmtTime
import ru.tsakunov.pravka.ui.lettersWord
import ru.tsakunov.pravka.ui.metricUnit
import ru.tsakunov.pravka.ui.metricValue
import ru.tsakunov.pravka.ui.theme.PravkaColors
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * График скорости письма по одному языку.
 * Точки — каждое слово (полые — записи с бумаги), сплошная линия — тренд (среднее за 5 слов),
 * пунктир — средняя за всё время, звезда — рекорд, подсветка — сегодняшние слова.
 */
@Composable
fun SpeedChart(
    attempts: List<Attempt>,
    metric: Metric,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val data = remember(attempts) { attempts.filter { it.letters > 0 && it.ms > 0 }.sortedBy { it.ts } }
    var selected by remember(data.size) { mutableIntStateOf(-1) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = PravkaColors.Muted)
    val smallBold = TextStyle(fontSize = 11.sp, color = PravkaColors.Ink2, fontWeight = FontWeight.SemiBold)

    Column(modifier) {
        if (data.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("Пока нет результатов", color = PravkaColors.Muted)
            }
            return@Column
        }

        val values = data.map { metricValue(it.secPerLetter, metric) }
        val lowerIsBetter = metric == Metric.SEC_PER_LETTER
        val bestIdx = data.indices.filter { data[it].letters >= MIN_LETTERS_FOR_RECORD }
            .let { idx -> if (lowerIsBetter) idx.minByOrNull { values[it] } else idx.maxByOrNull { values[it] } } ?: -1
        val totalMs = data.sumOf { it.ms }
        val totalLetters = data.sumOf { it.letters }
        val avgSpl = totalMs / 1000.0 / totalLetters
        val avgVal = metricValue(avgSpl, metric)
        val trend: List<Double?> = data.indices.map { i ->
            if (i < 4) null else {
                val win = data.subList(i - 4, i + 1)
                metricValue(win.sumOf { it.ms } / 1000.0 / win.sumOf { it.letters }, metric)
            }
        }

        // Подсказка по выбранной точке
        val sel = data.getOrNull(selected)
        Box(Modifier.fillMaxWidth().height(22.dp)) {
            if (sel != null) {
                val word = sel.word ?: lettersWord(sel.letters)
                Text(
                    "${word} · ${fmtTime(sel.ms)} · ${fmtRateValue(sel.secPerLetter, metric)} ${metricUnit(metric)}" +
                        if (sel.source == Attempt.SOURCE_PAPER) " · с бумаги" else "",
                    style = MaterialTheme.typography.bodySmall, color = PravkaColors.Ink2,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            } else {
                Text(
                    "Нажми на точку, чтобы увидеть слово",
                    style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }

        val n = data.size
        val pad = remember { floatArrayOf(40f, 14f, 14f, 26f) } // left, top, right, bottom (dp)

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(data) {
                    detectTapGestures { pos ->
                        val left = pad[0].dp.toPx(); val right = size.width - pad[2].dp.toPx()
                        val plotW = right - left
                        if (n == 1) { selected = if (selected == 0) -1 else 0; return@detectTapGestures }
                        val step = plotW / (n - 1)
                        val i = ((pos.x - left) / step).let { kotlin.math.round(it).toInt() }.coerceIn(0, n - 1)
                        selected = if (selected == i) -1 else i
                    }
                },
        ) {
            val left = pad[0].dp.toPx(); val top = pad[1].dp.toPx()
            val right = size.width - pad[2].dp.toPx(); val bottom = size.height - pad[3].dp.toPx()
            val plotW = right - left; val plotH = bottom - top

            val maxV = maxOf(values.max(), avgVal) * 1.12
            val (tickStep, niceMax) = niceTicks(maxV)
            fun yOf(v: Double) = (bottom - (v / niceMax) * plotH).toFloat()
            fun xOf(i: Int) = if (n == 1) left + plotW / 2 else left + plotW * i / (n - 1)

            // Полоса «сегодня»
            val todayIdx = data.indices.filter { isToday(data[it].ts) }
            if (todayIdx.isNotEmpty() && n > 1) {
                val half = plotW / (n - 1) / 2
                val x0 = (xOf(todayIdx.first()) - half).coerceAtLeast(left)
                val x1 = (xOf(todayIdx.last()) + half).coerceAtMost(right)
                drawRect(color.copy(alpha = 0.07f), topLeft = Offset(x0, top), size = Size(x1 - x0, plotH))
                val lbl = measurer.measure("сегодня", smallBold)
                drawText(lbl, topLeft = Offset(((x0 + x1) / 2 - lbl.size.width / 2).coerceIn(left, right - lbl.size.width), top))
            }

            // Сетка и подписи оси Y
            var v = 0.0
            while (v <= niceMax + 1e-9) {
                val y = yOf(v)
                drawLine(PravkaColors.Grid, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                val txt = measurer.measure(if (tickStep >= 1) v.toInt().toString() else String.format("%.1f", v).replace('.', ','), labelStyle)
                drawText(txt, topLeft = Offset(left - txt.size.width - 6.dp.toPx(), y - txt.size.height / 2))
                v += tickStep
            }
            // Ось X: номера слов
            val xLabels = if (n <= 8) (0 until n).toList() else listOf(0, n / 2, n - 1)
            for (i in xLabels) {
                val txt = measurer.measure((i + 1).toString(), labelStyle)
                drawText(txt, topLeft = Offset(xOf(i) - txt.size.width / 2, bottom + 6.dp.toPx()))
            }
            drawLine(PravkaColors.Axis, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1f)

            // Средняя за всё время (пунктир)
            val yAvg = yOf(avgVal)
            drawLine(
                PravkaColors.Ink2, Offset(left, yAvg), Offset(right, yAvg), strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
            val avgTxt = measurer.measure("средняя ${fmtRateValue(avgSpl, metric)}", smallBold)
            drawText(avgTxt, topLeft = Offset(right - avgTxt.size.width, yAvg - avgTxt.size.height - 2.dp.toPx()))

            // Тонкая линия по точкам
            if (n > 1) {
                val p = Path()
                for (i in 0 until n) { val x = xOf(i); val y = yOf(values[i]); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                drawPath(p, color.copy(alpha = 0.35f), style = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // Тренд (среднее за 5 слов)
            if (n >= 5) {
                val p = Path(); var started = false
                for (i in 0 until n) {
                    val t = trend[i] ?: continue
                    val x = xOf(i); val y = yOf(t)
                    if (!started) { p.moveTo(x, y); started = true } else p.lineTo(x, y)
                }
                drawPath(p, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // Точки
            val r = 4.5.dp.toPx()
            for (i in 0 until n) {
                val c = Offset(xOf(i), yOf(values[i]))
                val hollow = data[i].source == Attempt.SOURCE_PAPER
                drawCircle(PravkaColors.Surface, r + 2.dp.toPx(), c) // кольцо цвета фона
                if (hollow) {
                    drawCircle(PravkaColors.Surface, r, c)
                    drawCircle(color, r, c, style = Stroke(width = 2.dp.toPx()))
                } else drawCircle(color, r, c)
                if (i == selected) drawCircle(PravkaColors.Ink, r + 3.dp.toPx(), c, style = Stroke(width = 1.5.dp.toPx()))
            }
            // Звезда на рекорде
            if (bestIdx >= 0) {
                val c = Offset(xOf(bestIdx), yOf(values[bestIdx]) - 14.dp.toPx())
                drawPath(starPath(c, 7.dp.toPx()), PravkaColors.Gold)
                drawPath(starPath(c, 7.dp.toPx()), PravkaColors.GoldText, style = Stroke(1f))
            }
        }

        // Легенда
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendEntry("слово") { Box(Modifier.size(8.dp).background(color, CircleShape)) }
            if (data.any { it.source == Attempt.SOURCE_PAPER }) {
                LegendEntry("с бумаги") { Box(Modifier.size(8.dp).border(2.dp, color, CircleShape)) }
            }
            if (n >= 5) LegendEntry("тренд") { Box(Modifier.width(16.dp).height(2.dp).background(color, RoundedCornerShape(1.dp))) }
            LegendEntry("средняя") { Box(Modifier.width(16.dp).height(1.dp).background(PravkaColors.Ink2)) }
            LegendEntry("рекорд") { Text("★", color = PravkaColors.Gold, fontSize = 12.sp) }
        }
        Text(
            if (lowerIsBetter) "Секунд на букву: меньше = быстрее" else "Букв в минуту: больше = быстрее",
            style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun LegendEntry(label: String, mark: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
        Box(Modifier.height(14.dp), contentAlignment = Alignment.Center) { mark() }
        Text(label, style = MaterialTheme.typography.labelSmall, color = PravkaColors.Ink2)
    }
}

private fun niceTicks(maxV: Double): Pair<Double, Double> {
    if (maxV <= 0) return 1.0 to 4.0
    val rough = maxV / 4
    val mag = 10.0.pow(floor(log10(rough)))
    val norm = rough / mag
    val step = when {
        norm <= 1 -> 1.0
        norm <= 2 -> 2.0
        norm <= 2.5 -> 2.5
        norm <= 5 -> 5.0
        else -> 10.0
    } * mag
    val niceMax = ceil(maxV / step) * step
    return step to niceMax
}

private fun starPath(center: Offset, r: Float): Path {
    val p = Path()
    val inner = r * 0.45f
    for (k in 0 until 10) {
        val rad = if (k % 2 == 0) r else inner
        val a = Math.toRadians((-90 + k * 36).toDouble())
        val x = center.x + (rad * cos(a)).toFloat()
        val y = center.y + (rad * sin(a)).toFloat()
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

