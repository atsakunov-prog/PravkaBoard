package ru.tsakunov.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.domain.Task
import ru.tsakunov.pravka.domain.doneToday
import ru.tsakunov.pravka.domain.movedIds
import ru.tsakunov.pravka.domain.nextTask
import ru.tsakunov.pravka.domain.reorder
import ru.tsakunov.pravka.domain.tasksOf

class TasksTest {

    private val list = "L"
    private val items = listOf(
        item("a", "a hen", "курица"),
        item("b", "a goose", "гусь"),
        item("c", "wheat", ""),
        item("d", "", "работа"),
    )

    private fun item(id: String, en: String, ru: String, position: Int = id[0] - 'a') =
        WordItem(id = id, listId = list, en = en, ru = ru, kind = "word", position = position)

    /** Попытка «сегодня»: в тестах сегодняшними считаются метки времени от 1000. */
    private fun done(itemId: String, lang: Lang, ts: Long = 1000) =
        Attempt(id = "$itemId-${lang.code}-$ts", ts = ts, lang = lang.code, word = null, letters = 3, ms = 5000, source = Attempt.SOURCE_APP, listId = list, itemId = itemId)

    private val today: (Long) -> Boolean = { it >= 1000 }

    @Test
    fun tasks_englishFirstThenRussian_skippingBlanks() {
        val t = tasksOf(items)
        assertEquals(listOf("a hen", "a goose", "wheat", "курица", "гусь", "работа"), t.map { it.text })
        assertEquals(listOf(Lang.EN, Lang.EN, Lang.EN, Lang.RU, Lang.RU, Lang.RU), t.map { it.lang })
    }

    @Test
    fun next_staysInSameLanguage() {
        val next = nextTask(items, emptyList(), itemId = "a", lang = Lang.EN, today = today)
        assertEquals(Task(items[1], Lang.EN), next)
    }

    @Test
    fun next_wrapsWithinLanguageBeforeSwitching() {
        // Написаны a и c по-английски, b — нет: после c возвращаемся к b, а не идём в русский.
        val attempts = listOf(done("a", Lang.EN), done("c", Lang.EN))
        assertEquals(Task(items[1], Lang.EN), nextTask(items, attempts, "c", Lang.EN, today))
    }

    @Test
    fun next_switchesLanguageWhenCurrentIsFinished() {
        val attempts = listOf(done("a", Lang.EN), done("b", Lang.EN), done("c", Lang.EN))
        assertEquals(Task(items[0], Lang.RU), nextTask(items, attempts, "c", Lang.EN, today))
        // Из русского в английский тоже: русские написаны, английские нет.
        val ru = listOf(done("a", Lang.RU), done("b", Lang.RU), done("d", Lang.RU))
        assertEquals(Task(items[0], Lang.EN), nextTask(items, ru, "d", Lang.RU, today))
    }

    @Test
    fun next_nullWhenEverythingWrittenToday_andYesterdayDoesNotCount() {
        val all = tasksOf(items).map { done(it.item.id, it.lang) }
        assertNull(nextTask(items, all, "a", Lang.EN, today))
        val yesterday = tasksOf(items).map { done(it.item.id, it.lang, ts = 5) }
        assertEquals(Task(items[1], Lang.EN), nextTask(items, yesterday, "a", Lang.EN, today))
    }

    @Test
    fun next_currentItemDeleted_startsFromBeginningOfSameLanguage() {
        assertEquals(Task(items[0], Lang.EN), nextTask(items, emptyList(), "gone", Lang.EN, today))
    }

    @Test
    fun doneToday_picksLatestOfToday() {
        val attempts = listOf(done("a", Lang.EN, 1000), done("a", Lang.EN, 3000), done("a", Lang.EN, 2000), done("a", Lang.RU, 4000))
        assertEquals(3000L, doneToday(attempts, Task(items[0], Lang.EN), today)?.ts)
        assertNull(doneToday(attempts, Task(items[1], Lang.EN), today))
    }

    @Test
    fun reorder_keepsUnknownAtTail_andSkipsMissingIds() {
        val ordered = reorder(items, listOf("c", "a", "zzz", "c"), { it.id })
        assertEquals(listOf("c", "a", "b", "d"), ordered.map { it.id })
    }

    @Test
    fun movedIds_shiftsWithinBounds() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), movedIds(ids, "a", +1))
        assertEquals(listOf("a", "c", "b"), movedIds(ids, "c", -1))
        assertNull(movedIds(ids, "a", -1))
        assertNull(movedIds(ids, "c", +1))
        assertNull(movedIds(ids, "x", +1))
    }
}
