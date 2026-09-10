package ru.tsakunov.pravka.domain

import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.WordItem
import kotlin.math.roundToInt

/**
 * Одно и то же слово, написанное несколько раз. Показывает, что второй проход быстрее первого.
 * Слова объединяются по нормализованному тексту внутри языка («a hen» и «hen» — одно слово),
 * а если текста нет (старые записи с бумаги), то по itemId.
 */
data class RepeatRow(
    val key: String,
    val lang: Lang,
    /** Текст слова; null для записей с бумаги без слова. */
    val word: String?,
    val letters: Int,
    /** Все попытки в хронологическом порядке, минимум две. */
    val passes: List<Attempt>,
) {
    val first: Attempt get() = passes.first()
    val last: Attempt get() = passes.last()

    /** Изменение последнего прохода относительно первого в процентах. Отрицательное = быстрее. */
    val deltaPct: Int get() = ((last.ms - first.ms) * 100.0 / first.ms).roundToInt()

    /** Изменение второго прохода относительно первого в процентах. */
    val secondPassPct: Int get() = ((passes[1].ms - first.ms) * 100.0 / first.ms).roundToInt()
}

private val LEADING_PARTICLES = Regex("^(a|an|the|to)\\s+")

/** «A hen» → «hen», «To plant» → «plant», «тёплый» → «теплый», лишние пробелы схлопываются. */
fun normalizeWord(text: String?): String? {
    val t = text?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")?.replace('ё', 'е') ?: return null
    if (t.isEmpty()) return null
    return t.replace(LEADING_PARTICLES, "")
}

fun repeatKey(a: Attempt): String? {
    val w = normalizeWord(a.word)
    return when {
        !w.isNullOrEmpty() -> "${a.lang}:word:$w"
        a.itemId != null -> "${a.lang}:item:${a.itemId}"
        else -> null
    }
}

private fun buildRows(attempts: List<Attempt>): List<RepeatRow> =
    attempts
        .filter { it.letters > 0 && it.ms > 0 }
        .groupBy { repeatKey(it) }
        .mapNotNull { (key, list) ->
            if (key == null || list.size < 2) return@mapNotNull null
            val sorted = list.sortedBy { it.ts }
            RepeatRow(
                key = key,
                lang = sorted.first().langEnum,
                word = sorted.last().word ?: sorted.firstNotNullOfOrNull { it.word },
                letters = sorted.last().letters,
                passes = sorted,
            )
        }
        .sortedByDescending { it.last.ts }

/** Повторы по всем словам. */
fun repeatRows(attempts: List<Attempt>): List<RepeatRow> = buildRows(attempts)

/**
 * Повторы для конкретного урока: попытки из этого списка плюс любые попытки (в том числе с бумаги),
 * чьё слово совпадает с одним из слов урока.
 */
fun repeatRowsForList(attempts: List<Attempt>, listId: String, items: List<WordItem>): List<RepeatRow> {
    val keys = HashSet<String>()
    for (it in items) {
        normalizeWord(it.en)?.let { w -> keys += "${Lang.EN.code}:$w" }
        normalizeWord(it.ru)?.let { w -> keys += "${Lang.RU.code}:$w" }
    }
    val relevant = attempts.filter { a ->
        a.listId == listId || normalizeWord(a.word)?.let { w -> "${a.lang}:$w" in keys } == true
    }
    return buildRows(relevant)
}

/** Все попытки по одному слову урока (в любом списке и с бумаги), хронологически. */
fun attemptsForWord(attempts: List<Attempt>, lang: Lang, word: String): List<Attempt> {
    val w = normalizeWord(word) ?: return emptyList()
    return attempts.filter { it.lang == lang.code && it.letters > 0 && it.ms > 0 && normalizeWord(it.word) == w }.sortedBy { it.ts }
}

data class RepeatSummary(
    val words: Int,
    /** Типичный (медианный) сдвиг второго прохода к первому, в процентах. Отрицательное = быстрее. */
    val medianSecondPassPct: Int,
    /** Сколько слов во второй раз написаны быстрее. */
    val fasterCount: Int,
)

fun repeatSummary(rows: List<RepeatRow>): RepeatSummary? {
    if (rows.isEmpty()) return null
    val pcts = rows.map { (it.passes[1].ms - it.first.ms) * 100.0 / it.first.ms }.sorted()
    val median = if (pcts.size % 2 == 1) pcts[pcts.size / 2] else (pcts[pcts.size / 2 - 1] + pcts[pcts.size / 2]) / 2
    return RepeatSummary(
        words = rows.size,
        medianSecondPassPct = median.roundToInt(),
        fasterCount = rows.count { it.passes[1].ms < it.first.ms },
    )
}
