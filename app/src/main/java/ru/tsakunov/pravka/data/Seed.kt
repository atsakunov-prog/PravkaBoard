package ru.tsakunov.pravka.data

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Статистика, накопленная на бумаге (сентябрь 2026). Записывались число букв и время,
 * слова не сохранились. У трёх первых английских слов есть второй проход («англ 2»),
 * поэтому первый и второй проход связаны общим itemId и попадают в таблицу повторов.
 *
 * VERSION растёт при каждом дополнении; идентификаторы записей стабильны, поэтому повторный
 * посев (REPLACE) безопасен для уже установленного приложения.
 */
object Seed {
    const val VERSION = 2

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // Первый листок: 9 сентября
    private val ruDay1 = listOf(6 to 44, 6 to 57)
    private val enPass1 = listOf(
        4 to 36, 6 to 61, 5 to 34, 5 to 47, 4 to 22, 5 to 45,
        6 to 45, 4 to 24, 4 to 31, 5 to 28, 4 to 32,
    )

    // Добор 10 сентября: ещё русские слова и второй проход первых трёх английских
    private val ruDay2 = listOf(6 to 37, 4 to 25, 8 to 48, 7 to 48, 6 to 40, 6 to 44, 6 to 39, 7 to 76, 6 to 57)
    private val enPass2 = listOf(27, 31, 28)

    fun attempts(): List<Attempt> {
        val out = ArrayList<Attempt>()
        val day1 = at(2026, 9, 9, 12, 0)
        val day2 = at(2026, 9, 10, 16, 0)

        // Идентификаторы первых записей сохранены в том виде, в каком они были в версии 1:
        // ru: paper-ru-0..1, en: paper-en-2..12 (общий счётчик).
        var i = 0
        for ((letters, sec) in ruDay1) {
            out += Attempt(
                id = "paper-ru-$i", ts = day1 + i * 60_000L, lang = Lang.RU.code, word = null,
                letters = letters, ms = sec * 1000L, source = Attempt.SOURCE_PAPER,
            )
            i++
        }
        enPass1.forEachIndexed { k, (letters, sec) ->
            out += Attempt(
                id = "paper-en-$i", ts = day1 + i * 60_000L, lang = Lang.EN.code, word = null,
                letters = letters, ms = sec * 1000L, source = Attempt.SOURCE_PAPER,
                itemId = enWordId(k),
            )
            i++
        }
        ruDay2.forEachIndexed { k, (letters, sec) ->
            out += Attempt(
                id = "paper-ru2-$k", ts = day2 + k * 60_000L, lang = Lang.RU.code, word = null,
                letters = letters, ms = sec * 1000L, source = Attempt.SOURCE_PAPER,
            )
        }
        enPass2.forEachIndexed { k, sec ->
            out += Attempt(
                id = "paper-en2-$k", ts = day2 + 30 * 60_000L + k * 60_000L, lang = Lang.EN.code, word = null,
                letters = enPass1[k].first, ms = sec * 1000L, source = Attempt.SOURCE_PAPER,
                itemId = enWordId(k),
            )
        }
        return out
    }

    private fun enWordId(k: Int) = "paper-en-word-$k"
}
