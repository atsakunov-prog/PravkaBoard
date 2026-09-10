package ru.tsakunov.pravka.data

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Статистика, накопленная на бумаге до приложения (сентябрь 2026).
 * Записывались только число букв и время, слова не сохранились.
 */
object Seed {
    private val paperRu = listOf(6 to 44, 6 to 57)
    private val paperEn = listOf(
        4 to 36, 6 to 61, 5 to 34, 5 to 47, 4 to 22, 5 to 45,
        6 to 45, 4 to 24, 4 to 31, 5 to 28, 4 to 32,
    )

    fun attempts(): List<Attempt> {
        val base = LocalDateTime.of(2026, 9, 9, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val out = ArrayList<Attempt>()
        var i = 0
        for ((letters, sec) in paperRu) {
            out += Attempt(
                id = "paper-ru-$i", ts = base + i * 60_000L, lang = Lang.RU.code, word = null,
                letters = letters, ms = sec * 1000L, source = Attempt.SOURCE_PAPER,
            )
            i++
        }
        for ((letters, sec) in paperEn) {
            out += Attempt(
                id = "paper-en-$i", ts = base + i * 60_000L, lang = Lang.EN.code, word = null,
                letters = letters, ms = sec * 1000L, source = Attempt.SOURCE_PAPER,
            )
            i++
        }
        return out
    }
}
