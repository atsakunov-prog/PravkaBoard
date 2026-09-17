package ru.tsakunov.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.domain.PracticeMode
import ru.tsakunov.pravka.domain.Task
import ru.tsakunov.pravka.domain.attemptsToday
import ru.tsakunov.pravka.domain.autoMode
import ru.tsakunov.pravka.domain.decodeModeOverride
import ru.tsakunov.pravka.domain.effectiveMode
import ru.tsakunov.pravka.domain.encodeModeOverride
import java.time.LocalDate
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

    /** Все задания написаны по одному разу. */
    private fun onceEach() = tasksOf(items).map { done(it.item.id, it.lang) }

    @Test
    fun tasks_englishFirstThenRussian_skippingBlanks_withHelpers() {
        val t = tasksOf(items)
        assertEquals(listOf("a hen", "a goose", "wheat", "курица", "гусь", "работа"), t.map { it.text })
        assertEquals(listOf(Lang.EN, Lang.EN, Lang.EN, Lang.RU, Lang.RU, Lang.RU), t.map { it.lang })
        assertEquals(listOf("курица", "гусь", "", "a hen", "a goose", ""), t.map { it.helper })
    }

    @Test
    fun next_followsTheColumn_thenSwitchesLanguage() {
        assertEquals(Task(items[1], Lang.EN), nextTask(items, listOf(done("a", Lang.EN)), "a", Lang.EN, today))
        // После последнего английского — первое русское, а не первое английское по кругу.
        val en = listOf(done("a", Lang.EN), done("b", Lang.EN), done("c", Lang.EN))
        assertEquals(Task(items[0], Lang.RU), nextTask(items, en, "c", Lang.EN, today))
        // И обратно: после последнего русского лента заворачивается на первое английское.
        val ru = listOf(done("a", Lang.RU), done("b", Lang.RU), done("d", Lang.RU))
        assertEquals(Task(items[0], Lang.EN), nextTask(items, ru, "d", Lang.RU, today))
    }

    @Test
    fun next_skippedWordComesAroundWithTheTape() {
        // b пропустили: после c идёт русский столбик, b дождётся своего места на следующем витке.
        val attempts = listOf(done("a", Lang.EN), done("c", Lang.EN))
        assertEquals(Task(items[0], Lang.RU), nextTask(items, attempts, "c", Lang.EN, today))
        val ru = attempts + listOf(done("a", Lang.RU), done("b", Lang.RU), done("d", Lang.RU))
        assertEquals(Task(items[0], Lang.EN), nextTask(items, ru, "d", Lang.RU, today))
        assertEquals(Task(items[1], Lang.EN), nextTask(items, ru + done("a", Lang.EN, 2000), "a", Lang.EN, today))
    }

    @Test
    fun next_secondTimeAroundIsRecall_andEndsAfterTwice() {
        // Всё написано по разу: «Дальше» после последнего русского ведёт к первому английскому, уже по памяти.
        val next = nextTask(items, onceEach(), "d", Lang.RU, today)
        assertEquals(Task(items[0], Lang.EN), next)
        assertEquals(1, attemptsToday(onceEach(), next!!, today))
        val twice = onceEach() + tasksOf(items).map { done(it.item.id, it.lang, 2000) }
        assertNull(nextTask(items, twice, "d", Lang.RU, today))
        // Слово, написанное вручную в третий раз, ленту не оживляет.
        assertNull(nextTask(items, twice + done("a", Lang.EN, 3000), "a", Lang.EN, today))
    }

    @Test
    fun next_skipsWordsAlreadyWrittenTwice() {
        val attempts = onceEach() + listOf(done("b", Lang.EN, 2000), done("c", Lang.EN, 2000))
        // После a (написано раз) идут b и c, но они уже дважды: следующее — русское a.
        assertEquals(Task(items[0], Lang.RU), nextTask(items, attempts, "a", Lang.EN, today))
    }

    @Test
    fun next_fromListScreen_startsAtBeginning_yesterdayDoesNotCount() {
        assertEquals(Task(items[0], Lang.EN), nextTask(items, emptyList(), null, Lang.EN, today))
        val yesterday = tasksOf(items).map { done(it.item.id, it.lang, ts = 5) }
        assertEquals(Task(items[0], Lang.EN), nextTask(items, yesterday, null, Lang.EN, today))
        assertEquals(0, attemptsToday(yesterday, Task(items[0], Lang.EN), today))
    }

    @Test
    fun next_currentItemDeleted_startsFromBeginning() {
        assertEquals(Task(items[0], Lang.EN), nextTask(items, emptyList(), "gone", Lang.EN, today))
        assertNull(nextTask(emptyList(), emptyList(), null, Lang.EN, today))
    }

    @Test
    fun doneToday_andCount() {
        val attempts = listOf(done("a", Lang.EN, 1000), done("a", Lang.EN, 3000), done("a", Lang.EN, 2000), done("a", Lang.RU, 4000), done("a", Lang.EN, 5))
        assertEquals(3000L, doneToday(attempts, Task(items[0], Lang.EN), today)?.ts)
        assertEquals(3, attemptsToday(attempts, Task(items[0], Lang.EN), today))
        assertNull(doneToday(attempts, Task(items[1], Lang.EN), today))
        assertEquals(0, attemptsToday(attempts, Task(items[1], Lang.EN), today))
    }

    @Test
    fun autoMode_copyUntilEveryWordWrittenOnce() {
        assertEquals(PracticeMode.COPY, autoMode(items, emptyList(), today))
        assertEquals(PracticeMode.COPY, autoMode(items, onceEach().drop(1), today))
        assertEquals(PracticeMode.RECALL, autoMode(items, onceEach(), today))
        // Пустой список: писать нечего, но и учить нечего.
        assertEquals(PracticeMode.COPY, autoMode(emptyList(), emptyList(), today))
        // Выбор папы перекрывает автоматику в обе стороны.
        assertEquals(PracticeMode.RECALL, effectiveMode(PracticeMode.RECALL, items, emptyList(), today))
        assertEquals(PracticeMode.COPY, effectiveMode(PracticeMode.COPY, items, onceEach(), today))
        assertEquals(PracticeMode.RECALL, effectiveMode(null, items, onceEach(), today))
    }

    @Test
    fun modeOverride_roundTrips_andExpiresNextDay() {
        val day = LocalDate.of(2026, 9, 17)
        val v = encodeModeOverride(PracticeMode.RECALL, day)
        assertEquals("recall@2026-09-17", v)
        assertEquals(PracticeMode.RECALL, decodeModeOverride(v, day))
        assertNull(decodeModeOverride(v, day.plusDays(1)))
        assertNull(decodeModeOverride(null, day))
        assertNull(decodeModeOverride("garbage", day))
        assertNull(decodeModeOverride("dance@2026-09-17", day))
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
