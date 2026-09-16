package ru.tsakunov.pravka.domain

import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.WordItem

/** Слово в конкретном языке — единица тренировки в Гармошке. */
data class Task(val item: WordItem, val lang: Lang) {
    val text: String get() = if (lang == Lang.EN) item.en else item.ru
}

/**
 * Все задания списка: сначала английские слова по порядку списка, потом русские. Языки не чередуются,
 * чтобы Боря дописал страницу на одном языке и только потом брал ручку для другого.
 */
fun tasksOf(items: List<WordItem>): List<Task> =
    items.filter { it.en.isNotBlank() }.map { Task(it, Lang.EN) } +
        items.filter { it.ru.isNotBlank() }.map { Task(it, Lang.RU) }

/** Сегодняшняя попытка по заданию, последняя по времени; null — сегодня ещё не писал. */
fun doneToday(attempts: List<Attempt>, task: Task, today: (Long) -> Boolean = ::isToday): Attempt? =
    attempts.filter { it.itemId == task.item.id && it.lang == task.lang.code && today(it.ts) }.maxByOrNull { it.ts }

/**
 * Куда вести кнопка «Дальше»: следующее ненаписанное сегодня слово того же языка (после текущего, потом с
 * начала списка), а когда язык закончился — первое ненаписанное слово другого языка. null — всё написано.
 * Если текущего задания в списке уже нет (слово удалили), поиск идёт с начала того же языка.
 */
fun nextTask(items: List<WordItem>, attempts: List<Attempt>, itemId: String, lang: Lang, today: (Long) -> Boolean = ::isToday): Task? {
    val tasks = tasksOf(items)
    val same = tasks.filter { it.lang == lang }
    val idx = same.indexOfFirst { it.item.id == itemId }
    val ordered = if (idx >= 0) same.drop(idx + 1) + same.take(idx + 1) else same
    val other = tasks.filter { it.lang != lang }
    return (ordered + other).firstOrNull { doneToday(attempts, it, today) == null }
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
