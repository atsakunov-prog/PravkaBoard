package ru.tsakunov.pravka.domain

import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.WordItem

/**
 * Сколько раз слово пишется за день, прежде чем «Дальше» начнёт его пропускать. Как на бумаге: первый раз —
 * списывание (оба слова видны), второй — по памяти (видна только подсказка на другом языке, слово открывается по
 * нажатию для проверки). Режим решается по каждому слову отдельно: уже написанное сегодня идёт по памяти.
 */
const val ACCORDION_PASSES = 2

/** Слово в конкретном языке — единица тренировки в Гармошке. */
data class Task(val item: WordItem, val lang: Lang) {
    val text: String get() = if (lang == Lang.EN) item.en else item.ru
    /** Слово-подсказка на другом языке: перевод для английского, английское для русского. */
    val helper: String get() = if (lang == Lang.EN) item.ru else item.en
}

/**
 * Все задания списка: сначала английские слова по порядку списка, потом русские. Языки не чередуются,
 * чтобы Боря дописал столбик на одном языке и только потом брал ручку для другого.
 */
fun tasksOf(items: List<WordItem>): List<Task> =
    items.filter { it.en.isNotBlank() }.map { Task(it, Lang.EN) } +
        items.filter { it.ru.isNotBlank() }.map { Task(it, Lang.RU) }

/** Сегодняшняя попытка по заданию, последняя по времени; null — сегодня ещё не писал. */
fun doneToday(attempts: List<Attempt>, task: Task, today: (Long) -> Boolean = ::isToday): Attempt? =
    attempts.filter { it.itemId == task.item.id && it.lang == task.lang.code && today(it.ts) }.maxByOrNull { it.ts }

/** Сколько раз задание написано сегодня: 0 — ещё не писал (списывание), от 1 — уже писал (по памяти). */
fun attemptsToday(attempts: List<Attempt>, task: Task, today: (Long) -> Boolean = ::isToday): Int =
    attempts.count { it.itemId == task.item.id && it.lang == task.lang.code && today(it.ts) }

/**
 * Куда вести кнопка «Дальше». Задания идут одной лентой, как столбики гармошки: все английские, потом все
 * русские, потом снова с начала. Берётся следующее за текущим слово, которое сегодня написано меньше passes раз;
 * списывание это или по памяти, решает само слово (см. attemptsToday). null — каждое слово написано passes раз.
 * itemId == null — с начала ленты (кнопка на экране списка); если текущего задания уже нет (слово удалили),
 * поиск тоже идёт с начала.
 */
fun nextTask(
    items: List<WordItem>,
    attempts: List<Attempt>,
    itemId: String?,
    lang: Lang,
    today: (Long) -> Boolean = ::isToday,
    passes: Int = ACCORDION_PASSES,
): Task? {
    val tasks = tasksOf(items)
    val idx = tasks.indexOfFirst { it.item.id == itemId && it.lang == lang }
    val ordered = if (idx >= 0) tasks.drop(idx + 1) + tasks.take(idx + 1) else tasks
    return ordered.firstOrNull { attemptsToday(attempts, it, today) < passes }
}

/**
 * Новый порядок списка: сначала известные идентификаторы в заданном порядке, затем всё, чего в нём не
 * оказалось (слово добавили на другом устройстве), в прежнем порядке. Неизвестные идентификаторы пропускаются.
 */
fun <T> reorder(items: List<T>, orderedIds: List<String>, id: (T) -> String): List<T> {
    val byId = items.associateBy(id)
    val head = orderedIds.distinct().mapNotNull { byId[it] }
    val used = head.map(id).toHashSet()
    return head + items.filter { id(it) !in used }
}

/** Идентификаторы после сдвига одного элемента на delta позиций (−1 выше, +1 ниже); null — двигать некуда. */
fun movedIds(ids: List<String>, movedId: String, delta: Int): List<String>? {
    val from = ids.indexOf(movedId)
    if (from < 0) return null
    val to = from + delta
    if (to < 0 || to >= ids.size || to == from) return null
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}
