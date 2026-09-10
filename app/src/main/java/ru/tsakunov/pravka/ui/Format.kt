package ru.tsakunov.pravka.ui

import ru.tsakunov.pravka.data.Metric
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val RU = Locale.forLanguageTag("ru-RU")

/** 0:45 или, с десятыми, 0:45,3 */
fun fmtTime(ms: Long, tenths: Boolean = false): String {
    val totalTenths = (ms / 100.0).roundToInt()
    val sec = totalTenths / 10
    val t = totalTenths % 10
    val m = sec / 60
    val s = sec % 60
    val base = "%d:%02d".format(m, s)
    return if (tenths) "$base,$t" else base
}

/** «1 ч 12 мин», «12 мин», «40 с» */
fun fmtDuration(ms: Long): String {
    val totalSec = (ms / 1000.0).roundToInt()
    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60
    return when {
        h > 0 -> if (m > 0) "$h ч $m мин" else "$h ч"
        m > 0 -> "$m мин"
        else -> "$s с"
    }
}

/** 7,5 */
fun fmtNum(x: Double, digits: Int = 1): String =
    String.format(RU, "%.${digits}f", x).replace('.', ',')

fun fmtRateValue(secPerLetter: Double, metric: Metric): String = when (metric) {
    Metric.SEC_PER_LETTER -> fmtNum(secPerLetter, 1)
    Metric.LETTERS_PER_MIN -> fmtNum(60.0 / secPerLetter, if (60.0 / secPerLetter >= 10) 0 else 1)
}

fun metricUnit(metric: Metric): String = when (metric) {
    Metric.SEC_PER_LETTER -> "с/букву"
    Metric.LETTERS_PER_MIN -> "букв/мин"
}

fun metricValue(secPerLetter: Double, metric: Metric): Double = when (metric) {
    Metric.SEC_PER_LETTER -> secPerLetter
    Metric.LETTERS_PER_MIN -> 60.0 / secPerLetter
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val n10 = n % 10
    val n100 = n % 100
    val form = when {
        n10 == 1 && n100 != 11 -> one
        n10 in 2..4 && n100 !in 12..14 -> few
        else -> many
    }
    return "$n $form"
}

fun lettersWord(n: Int) = plural(n, "буква", "буквы", "букв")
fun wordsWord(n: Int) = plural(n, "слово", "слова", "слов")

private val dateFmt = DateTimeFormatter.ofPattern("d MMM", RU)
private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)

fun fmtDate(ts: Long): String = dateFmt.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
fun fmtDateTime(ts: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

/** Разбор «0:45», «45», «1:02», «0.45» в миллисекунды. */
fun parseTimeInput(text: String): Long? {
    val t = text.trim().replace(',', '.')
    if (t.isEmpty()) return null
    val parts = t.split(':')
    return try {
        val ms = when (parts.size) {
            1 -> (parts[0].toDouble() * 1000).roundToInt().toLong()
            2 -> (parts[0].toInt() * 60_000L) + (parts[1].toDouble() * 1000).roundToInt()
            else -> null
        }
        ms?.takeIf { it > 0 }
    } catch (_: IllegalArgumentException) { // NumberFormatException и NaN/бесконечность при округлении
        null
    }
}
