package ru.tsakunov.pravka.domain

import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang

/** Сколько Боря пишет слово из N букв: типичное и лучшее время. */
data class LengthStat(
    val letters: Int,
    val n: Int,
    /** Среднее время на слово такой длины, мс. */
    val avgMs: Long,
    val bestMs: Long,
    /** Средняя скорость: секунд на букву. */
    val secPerLetter: Double,
)

fun statsByLength(lang: Lang, attempts: List<Attempt>): List<LengthStat> =
    attempts
        .filter { it.lang == lang.code && it.letters > 0 && it.ms > 0 }
        .groupBy { it.letters }
        .map { (letters, list) ->
            val total = list.sumOf { it.ms }
            LengthStat(
                letters = letters,
                n = list.size,
                avgMs = total / list.size,
                bestMs = list.minOf { it.ms },
                secPerLetter = total / 1000.0 / (letters * list.size),
            )
        }
        .sortedBy { it.letters }
