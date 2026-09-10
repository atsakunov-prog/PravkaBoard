package ru.tsakunov.pravka.data

import ru.tsakunov.pravka.domain.countLetters
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Статистика, накопленная на бумаге (сентябрь 2026) по словам третьего урока (The Little Red Hen).
 *
 * Английские слова записаны так, как Боря их писал: вместе с артиклем и частицей «to»,
 * буквы считаются по тому же правилу, что и в приложении. Каждое английское слово написано
 * дважды (первый и второй проход), обе попытки связаны общим itemId. Русские слова написаны один раз,
 * порядок слов восстановлен папой по памяти, буквы тоже считаются по самим словам.
 *
 * VERSION растёт при каждом дополнении; идентификаторы записей стабильны, поэтому повторный
 * посев (REPLACE) безопасен для уже установленного приложения.
 */
object Seed {
    const val VERSION = 5

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // Русский: слово и секунды. Первые два — с первого листка (9 сентября), остальные — добор 10 сентября.
    // Версия 5: на листке у строки 0:25 стояла пометка «4 буквы», это гусь, а не прогулка; времена поменяны местами.
    private val ruDay1 = listOf("курица" to 44, "гусь" to 25)
    private val ruDay2 = listOf(
        "семя" to 37, "прогулка" to 57, "работа" to 48, "пшеница" to 48, "сажать" to 40,
        "делать" to 44, "растить" to 39, "резать" to 76, "теплый" to 57,
    )

    // Английский: слово, первый проход (9 сентября), второй проход (10 сентября), секунды
    private val en = listOf(
        Triple("a hen", 36, 27),
        Triple("a goose", 61, 31),
        Triple("a seed", 34, 28),
        Triple("a walk", 47, 40),
        Triple("work", 22, 37),
        Triple("wheat", 45, 61),
        Triple("to plant", 45, 34),
        Triple("to do", 24, 12),
        Triple("to grow", 31, 25),
        Triple("to cut", 28, 17),
        Triple("warm", 32, 28),
    )

    fun attempts(): List<Attempt> {
        val out = ArrayList<Attempt>()
        val day1 = at(2026, 9, 9, 12, 0)
        val day2 = at(2026, 9, 10, 16, 0)

        // Идентификаторы первых записей сохранены как в версии 1: ru paper-ru-0..1, en paper-en-2..12.
        var i = 0
        for ((word, sec) in ruDay1) {
            out += Attempt(
                id = "paper-ru-$i", ts = day1 + i * 60_000L, lang = Lang.RU.code, word = word,
                letters = countLetters(word), ms = sec * 1000L, source = Attempt.SOURCE_PAPER,
            )
            i++
        }
        en.forEachIndexed { k, (word, sec1, _) ->
            out += Attempt(
                id = "paper-en-$i", ts = day1 + i * 60_000L, lang = Lang.EN.code, word = word,
                letters = countLetters(word), ms = sec1 * 1000L, source = Attempt.SOURCE_PAPER,
                itemId = enWordId(k),
            )
            i++
        }
        ruDay2.forEachIndexed { k, (word, sec) ->
            out += Attempt(
                id = "paper-ru2-$k", ts = day2 + k * 60_000L, lang = Lang.RU.code, word = word,
                letters = countLetters(word), ms = sec * 1000L, source = Attempt.SOURCE_PAPER,
            )
        }
        en.forEachIndexed { k, (word, _, sec2) ->
            out += Attempt(
                id = "paper-en2-$k", ts = day2 + 30 * 60_000L + k * 60_000L, lang = Lang.EN.code, word = word,
                letters = countLetters(word), ms = sec2 * 1000L, source = Attempt.SOURCE_PAPER,
                itemId = enWordId(k),
            )
        }
        return out
    }

    private fun enWordId(k: Int) = "paper-en-word-$k"
}
