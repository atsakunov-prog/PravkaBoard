package ru.tsakunov.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.Seed
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.domain.normalizeWord
import ru.tsakunov.pravka.domain.repeatRows
import ru.tsakunov.pravka.domain.repeatRowsForList
import ru.tsakunov.pravka.domain.statsByLength
import ru.tsakunov.pravka.domain.repeatSummary
import ru.tsakunov.pravka.domain.statsFor

class RepeatsTest {

    @Test
    fun seed_v4_countsAndPairs() {
        val seed = Seed.attempts()
        assertEquals(11, statsFor(Lang.RU, seed).n)
        assertEquals(22, statsFor(Lang.EN, seed).n)
        assertEquals(seed.size, seed.map { it.id }.toSet().size) // идентификаторы уникальны

        val rows = repeatRows(seed)
        assertEquals(11, rows.size)
        assertTrue(rows.all { it.lang == Lang.EN && it.passes.size == 2 })
        val byWord = rows.associateBy { it.word }
        assertEquals(-25, byWord["a hen"]!!.deltaPct)     // 0:36 -> 0:27
        assertEquals(-49, byWord["a goose"]!!.deltaPct)   // 1:01 -> 0:31
        assertEquals(68, byWord["work"]!!.deltaPct)       // 0:22 -> 0:37, медленнее
        assertEquals(-50, byWord["to do"]!!.deltaPct)     // 0:24 -> 0:12
        assertEquals(7, byWord["to plant"]!!.letters)

        val s = repeatSummary(rows)
        assertNotNull(s)
        assertEquals(11, s!!.words)
        assertEquals(9, s.fasterCount)
        assertEquals(-19, s.medianSecondPassPct) // медиана по 11 словам
    }

    @Test
    fun normalizeWord_stripsArticlesAndTo() {
        assertEquals("hen", normalizeWord("A hen"))
        assertEquals("plant", normalizeWord("to plant"))
        assertEquals("little red hen.", normalizeWord("  The  little red hen. "))
        assertEquals(null, normalizeWord("   "))
        assertEquals("курица", normalizeWord("Курица"))
        assertEquals("теплый", normalizeWord("Тёплый"))
    }

    @Test
    fun listRows_includePaperAttemptsForSameWords() {
        val seed = Seed.attempts()
        val list = "L3"
        val items = listOf(
            WordItem(id = "i1", listId = list, en = "hen", ru = "курица", kind = "word", position = 0),
            WordItem(id = "i2", listId = list, en = "to plant", ru = "сажать", kind = "word", position = 1),
        )
        // Сегодняшняя попытка в приложении по слову без артикля объединяется с бумажным «a hen».
        val today = Attempt(
            id = "app-1", ts = System.currentTimeMillis(), lang = Lang.EN.code, word = "hen", letters = 3, ms = 20_000,
            source = "app", listId = list, itemId = "i1",
        )
        val rows = repeatRowsForList(seed + today, list, items)
        assertEquals(2, rows.size)
        val hen = rows.first { normalizeWord(it.word) == "hen" }
        assertEquals(3, hen.passes.size)
        assertEquals("hen", hen.word) // подпись берётся из последней попытки
        assertEquals(2, rows.first { normalizeWord(it.word) == "plant" }.passes.size)
        // Русские бумажные слова написаны по одному разу, повторов у них ещё нет
        assertTrue(rows.all { it.lang == Lang.EN })
    }

    @Test
    fun statsByLength_groupsWords() {
        val seed = Seed.attempts()
        val en = statsByLength(Lang.EN, seed)
        assertEquals(listOf(4, 5, 6, 7), en.map { it.letters })
        val four = en.first { it.letters == 4 }
        assertEquals(8, four.n) // a hen, work, to do, warm — по два прохода
        assertEquals(12_000L, four.bestMs)
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
        val hen = rows.first { normalizeWord(it.word) == "hen" }
        assertEquals(2, hen.passes.size)
        assertEquals("Hen", hen.word) // подпись из последней попытки

        val onlyL1 = repeatRowsForList(all, "L1", listOf(WordItem("i3", "L1", "cut", "резать", "word", 0)))
        assertEquals(1, onlyL1.size)
        assertEquals("cut", onlyL1.single().word)
    }
}
