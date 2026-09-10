package ru.tsakunov.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.Seed
import ru.tsakunov.pravka.domain.repeatRows
import ru.tsakunov.pravka.domain.repeatSummary
import ru.tsakunov.pravka.domain.statsFor

class RepeatsTest {

    @Test
    fun seed_v2_countsAndPairs() {
        val seed = Seed.attempts()
        assertEquals(11, statsFor(Lang.RU, seed).n)
        assertEquals(14, statsFor(Lang.EN, seed).n) // 11 первых проходов + 3 вторых
        assertEquals(seed.size, seed.map { it.id }.toSet().size) // идентификаторы уникальны

        val rows = repeatRows(seed)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.lang == Lang.EN && it.passes.size == 2 })
        // 4 буквы: 0:36 -> 0:27 (−25%), 6 букв: 1:01 -> 0:31 (−49%), 5 букв: 0:34 -> 0:28 (−18%)
        val byLetters = rows.associateBy { it.letters }
        assertEquals(-25, byLetters[4]!!.deltaPct)
        assertEquals(-49, byLetters[6]!!.deltaPct)
        assertEquals(-18, byLetters[5]!!.deltaPct)

        val s = repeatSummary(rows)
        assertNotNull(s)
        assertEquals(3, s!!.words)
        assertEquals(3, s.fasterCount)
        assertEquals(-31, s.avgSecondPassPct)
    }

    @Test
    fun rows_groupByWordAcrossLists_andByListWhenScoped() {
        fun a(id: String, word: String, ms: Long, ts: Long, listId: String, itemId: String) = Attempt(
            id = id, ts = ts, lang = Lang.EN.code, word = word, letters = 3, ms = ms, source = "app", listId = listId, itemId = itemId,
        )
        val all = listOf(
            a("1", "hen", 30_000, 1, "L1", "i1"),
            a("2", "Hen", 20_000, 2, "L2", "i2"), // то же слово в другом списке
            a("3", "cut", 25_000, 3, "L1", "i3"),
            a("4", "cut", 24_000, 4, "L1", "i3"),
            a("5", "cut", 18_000, 5, "L1", "i3"),
        )
        val rows = repeatRows(all)
        assertEquals(2, rows.size)
        val cut = rows.first { it.word == "cut" }
        assertEquals(3, cut.passes.size)
        assertEquals(-28, cut.deltaPct)
        assertEquals(-4, cut.secondPassPct)
        val hen = rows.first { it.word == "hen" }
        assertEquals(2, hen.passes.size)

        val onlyL1 = repeatRows(all, listId = "L1")
        assertEquals(1, onlyL1.size)
        assertEquals("cut", onlyL1.single().word)
    }
}
