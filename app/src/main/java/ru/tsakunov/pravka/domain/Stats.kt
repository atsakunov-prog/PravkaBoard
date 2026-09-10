package ru.tsakunov.pravka.domain

import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Рекордом считаем только слова от 3 букв: на «do» скорость искусственно высокая. */
const val MIN_LETTERS_FOR_RECORD = 3

val MILESTONES = listOf(100, 250, 500, 1000, 2000, 3000, 5000, 10000)

/** Считаем только буквы: пробелы, дефисы, апострофы и знаки препинания не в счёт. */
fun countLetters(text: String): Int = text.count { it.isLetter() }

fun isToday(ts: Long): Boolean =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()

data class LangStats(
    val n: Int,
    /** Взвешенная средняя: всё время / все буквы, сек на букву. */
    val avg: Double?,
    val best: Attempt?,
    /** Средняя по последним 5 словам. */
    val last5: Double?,
    val totalLetters: Int,
    val totalMs: Long,
)

fun statsFor(lang: Lang, all: List<Attempt>): LangStats {
    val list = all.filter { it.lang == lang.code && it.letters > 0 && it.ms > 0 }.sortedBy { it.ts }
    if (list.isEmpty()) return LangStats(0, null, null, null, 0, 0)
    val totalMs = list.sumOf { it.ms }
    val totalLetters = list.sumOf { it.letters }
    val best = list.filter { it.letters >= MIN_LETTERS_FOR_RECORD }.minByOrNull { it.secPerLetter }
    val tail = list.takeLast(5)
    val last5 = tail.sumOf { it.ms } / 1000.0 / tail.sumOf { it.letters }
    return LangStats(list.size, totalMs / 1000.0 / totalLetters, best, last5, totalLetters, totalMs)
}

enum class VerdictKind { FIRST, RECORD, FASTER, NEUTRAL }

data class Verdict(
    val kind: VerdictKind,
    val rate: Double,
    val prevBest: Double?,
    val avg: Double?,
    /** Сколько попыток подряд (по этому языку) быстрее общей средней. */
    val streak: Int,
    /** Рубеж по общему числу букв, пройденный этой попыткой. */
    val milestone: Int?,
    val totalLetters: Int,
) {
    val celebrate: Boolean get() = kind == VerdictKind.RECORD || milestone != null
}

/** Оценка попытки относительно истории (история уже содержит саму попытку). */
fun evaluate(attempt: Attempt, all: List<Attempt>): Verdict {
    val lang = attempt.langEnum
    val prior = all.filter { it.lang == lang.code && it.id != attempt.id && it.letters > 0 && it.ms > 0 }
    val st = statsFor(lang, prior)
    val rate = attempt.secPerLetter
    val prevBest = st.best?.secPerLetter
    val kind = when {
        prior.isEmpty() -> VerdictKind.FIRST
        attempt.letters >= MIN_LETTERS_FOR_RECORD && (prevBest == null || rate < prevBest) -> VerdictKind.RECORD
        st.avg != null && rate < st.avg -> VerdictKind.FASTER
        else -> VerdictKind.NEUTRAL
    }
    val withThis = all.filter { it.lang == lang.code && it.letters > 0 && it.ms > 0 }.sortedBy { it.ts }
    val avgAll = statsFor(lang, withThis).avg ?: rate
    var streak = 0
    for (a in withThis.asReversed()) {
        if (a.secPerLetter < avgAll) streak++ else break
    }
    val total = all.sumOf { it.letters }
    val before = total - attempt.letters
    val milestone = MILESTONES.firstOrNull { before < it && total >= it }
    return Verdict(kind, rate, prevBest, st.avg, streak, milestone, total)
}
