package ru.tsakunov.pravka.domain

import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import kotlin.math.roundToInt

/**
 * Одно и то же слово, написанное несколько раз. Показывает, что второй проход быстрее первого.
 * Слова объединяются по тексту (без учёта регистра) внутри языка, а если текста нет
 * (записи с бумаги), то по itemId.
 */
data class RepeatRow(
    val key: String,
    val lang: Lang,
    /** Текст слова; null для записей с бумаги. */
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

fun repeatKey(a: Attempt): String? {
    val w = a.word?.trim()?.lowercase()
    return when {
        !w.isNullOrEmpty() -> "${a.lang}:word:$w"
        a.itemId != null -> "${a.lang}:item:${a.itemId}"
        else -> null
    }
}

fun repeatRows(attempts: List<Attempt>, listId: String? = null): List<RepeatRow> {
    val filtered = attempts.filter { it.letters > 0 && it.ms > 0 && (listId == null || it.listId == listId) }
    return filtered
        .groupBy { repeatKey(it) }
        .mapNotNull { (key, list) ->
            if (key == null || list.size < 2) return@mapNotNull null
            val sorted = list.sortedBy { it.ts }
            RepeatRow(
                key = key,
                lang = sorted.first().langEnum,
                word = sorted.firstNotNullOfOrNull { it.word },
                letters = sorted.first().letters,
                passes = sorted,
            )
        }
        .sortedByDescending { it.last.ts }
}

data class RepeatSummary(
    val words: Int,
    /** Средний сдвиг второго прохода к первому, в процентах. Отрицательное = быстрее. */
    val avgSecondPassPct: Int,
    /** Сколько слов во второй раз написаны быстрее. */
    val fasterCount: Int,
)

fun repeatSummary(rows: List<RepeatRow>): RepeatSummary? {
    if (rows.isEmpty()) return null
    val pcts = rows.map { (it.passes[1].ms - it.first.ms) * 100.0 / it.first.ms }
    return RepeatSummary(
        words = rows.size,
        avgSecondPassPct = (pcts.sum() / pcts.size).roundToInt(),
        fasterCount = rows.count { it.passes[1].ms < it.first.ms },
    )
}
