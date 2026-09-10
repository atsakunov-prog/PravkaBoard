package ru.tsakunov.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.Seed
import ru.tsakunov.pravka.domain.VerdictKind
import ru.tsakunov.pravka.domain.countLetters
import ru.tsakunov.pravka.domain.evaluate
import ru.tsakunov.pravka.domain.statsFor
import ru.tsakunov.pravka.ui.fmtNum
import ru.tsakunov.pravka.ui.fmtTime
import ru.tsakunov.pravka.ui.lettersWord
import ru.tsakunov.pravka.ui.parseTimeInput

class StatsTest {

    private fun attempt(id: String, lang: Lang, word: String?, letters: Int, sec: Double, ts: Long) = Attempt(
        id = id, ts = ts, lang = lang.code, word = word, letters = letters, ms = (sec * 1000).toLong(), source = "app",
    )

    @Test
    fun countLetters_ignoresSpacesAndPunctuation() {
        assertEquals(3, countLetters("hen"))
        assertEquals(5, countLetters("geese"))
        assertEquals(6, countLetters("курица"))
        assertEquals(21, countLetters("Here is the little red hen."))
        assertEquals(4, countLetters("a hen"))
        assertEquals(7, countLetters("to plant"))
        assertEquals(6, countLetters("Пойдём!"))
        assertEquals(0, countLetters("123 - !"))
    }

    @Test
    fun seed_matchesPaperNotes() {
        val seed = Seed.attempts()
        val en = statsFor(Lang.EN, seed)
        val ru = statsFor(Lang.RU, seed)
        assertEquals(22, en.n) // 11 слов по два прохода
        assertEquals(11, ru.n)
        // EN: 55 букв (с артиклями и «to») дважды, 405 + 340 секунд; RU: 66 букв за 515 секунд
        assertEquals(110, en.totalLetters)
        assertEquals(745_000L, en.totalMs)
        assertEquals(745.0 / 110, en.avg!!, 1e-9)
        assertEquals(66, ru.totalLetters)
        assertEquals(515.0 / 66, ru.avg!!, 1e-9)
        assertEquals("курица", seed.first { it.lang == "ru" }.word)
        // Лучший EN на бумаге: «to do» (4 буквы) за 12 секунд во второй проход
        assertEquals("to do", en.best!!.word)
        assertEquals(3.0, en.best!!.secPerLetter, 1e-9)
    }

    @Test
    fun evaluate_recordAndFasterAndNeutral() {
        val base = Seed.attempts()
        val now = System.currentTimeMillis()

        val record = attempt("r", Lang.EN, "hen", 3, 8.0, now) // 2,67 с/б < 3,0
        val v1 = evaluate(record, base + record)
        assertEquals(VerdictKind.RECORD, v1.kind)
        assertEquals(3.0, v1.prevBest!!, 1e-9)

        val faster = attempt("f", Lang.EN, "goose", 5, 30.0, now + 1) // 6,0 < средняя 6,77, но > рекорда
        val v2 = evaluate(faster, base + faster)
        assertEquals(VerdictKind.FASTER, v2.kind)

        val slow = attempt("s", Lang.EN, "wheat", 5, 60.0, now + 2) // 12 с/б
        val v3 = evaluate(slow, base + slow)
        assertEquals(VerdictKind.NEUTRAL, v3.kind)
        assertEquals(0, v3.streak)

        // Двухбуквенное слово не может стать рекордом
        val tiny = attempt("t", Lang.EN, "do", 2, 4.0, now + 3) // 2 с/б
        val v4 = evaluate(tiny, base + tiny)
        assertEquals(VerdictKind.FASTER, v4.kind)
    }

    @Test
    fun evaluate_firstAttemptOfLanguage() {
        val a = attempt("x", Lang.RU, "гусь", 4, 30.0, 1L)
        val v = evaluate(a, listOf(a))
        assertEquals(VerdictKind.FIRST, v.kind)
        assertNull(v.prevBest)
        assertNull(v.avg)
    }

    @Test
    fun evaluate_milestoneCrossing() {
        val base = Seed.attempts() // 176 букв
        assertEquals(176, base.sumOf { it.letters })
        val small = attempt("m1", Lang.EN, "Here is the little red hen. Who can help me?", 36, 300.0, 5L)
        assertNull(evaluate(small, base + small).milestone) // 212, рубеж 250 не пройден
        val big = attempt("m2", Lang.EN, "long dictation", 115, 900.0, 6L)
        val v = evaluate(big, base + big) // 291, пройден рубеж 250
        assertEquals(250, v.milestone)
        assertTrue(v.celebrate)
    }

    @Test
    fun streak_countsConsecutiveFasterThanAverage() {
        val now = 1_000_000L
        val list = listOf(
            attempt("1", Lang.RU, "a", 5, 50.0, now),      // 10
            attempt("2", Lang.RU, "b", 5, 50.0, now + 1),  // 10
            attempt("3", Lang.RU, "c", 5, 25.0, now + 2),  // 5
            attempt("4", Lang.RU, "d", 5, 25.0, now + 3),  // 5
            attempt("5", Lang.RU, "e", 5, 20.0, now + 4),  // 4
        )
        val v = evaluate(list.last(), list)
        // средняя 170/25 = 6,8; последние три (5, 5, 4) быстрее
        assertEquals(3, v.streak)
        assertNotNull(v.avg)
    }

    @Test
    fun formatting() {
        assertEquals("0:45", fmtTime(45_000))
        assertEquals("1:01", fmtTime(61_000))
        assertEquals("0:21,4", fmtTime(21_400, tenths = true))
        assertEquals("0:00,0", fmtTime(0, tenths = true))
        assertEquals("7,5", fmtNum(7.5))
        assertEquals("1 буква", lettersWord(1))
        assertEquals("3 буквы", lettersWord(3))
        assertEquals("11 букв", lettersWord(11))
        assertEquals("21 буква", lettersWord(21))
    }

    @Test
    fun parseTime() {
        assertEquals(45_000L, parseTimeInput("0:45"))
        assertEquals(61_000L, parseTimeInput("1:01"))
        assertEquals(45_000L, parseTimeInput("45"))
        assertEquals(45_500L, parseTimeInput("45,5"))
        assertNull(parseTimeInput(""))
        assertNull(parseTimeInput("abc"))
        assertNull(parseTimeInput("0:00"))
    }
}
