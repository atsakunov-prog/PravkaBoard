package ru.tsakunov.pravka.domain

import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.WordItem

/**
 * Сколько кругов гармошки за день. Как на бумаге: первый круг — списывание (столбик английских, рядом столбик
 * русских, оба слова видны), второй — по памяти (столбик с оригиналом заворачивается, видна только подсказка на
 * другом языке, слово открывается по нажатию для проверки).
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

/** Сколько раз задание написано сегодня: 0 — ещё не писал, 1 — идёт второй круг (по памяти). */
fun attemptsToday(attempts: List<Attempt>, task: Task, today: (Long) -> Boolean = ::isToday): Int =
    attempts.count { it.itemId == task.item.id && it.lang == task.lang.code && today(it.ts) }

/**
 * Куда вести кнопка «Дальше». Круги идут по порядку, столбики внутри круга — английский, потом русский. Пока
 * столбик не закончен, продолжаем его: недописанные слова текущего языка после текущего слова, потом с начала
 * столбика, затем другой язык; когда круг закрыт, следующий начинается с первого английского слова, пока не
 * пройдено passes кругов. null — всё написано. itemId == null — начать с начала (кнопка на экране списка);
 * если текущего задания в списке уже нет (слово удалили), поиск идёт с начала столбика.
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
    val current = tasks.firstOrNull { it.item.id == itemId && it.lang == lang }
    // Продолжать после текущего слова имеет смысл только в круге, который оно только что закрыло;
    // следующий круг начинается с начала столбика.
    val justClosed = current?.let { attemptsToday(attempts, it, today) - 1 } ?: -1
    for (pass in 0 until passes) {
        // Свой столбик первым только в круге, который ещё дописывается; остальные круги идут в порядке гармошки.
        val langs = if (pass == justClosed) listOf(lang, lang.other()) else listOf(Lang.EN, Lang.RU)
        for (l in langs) {
            val column = tasks.filter { it.lang == l }
            val idx = if (l == lang && pass == justClosed) column.indexOfFirst { it.item.id == itemId } else -1
            val ordered = if (idx >= 0) column.drop(idx + 1) + column.take(idx + 1) else column
            ordered.firstOrNull { attemptsToday(attempts, it, today) <= pass }?.let { return it }
        }
    }
    return null
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
