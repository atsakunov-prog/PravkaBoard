package ru.tsakunov.pravka.domain

import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.WordItem
import java.time.LocalDate

/**
 * Сколько раз слово пишется за день, прежде чем «Дальше» начнёт его пропускать. Как на бумаге: первый раз —
 * списывание, второй — по памяти.
 */
const val ACCORDION_PASSES = 2

/** Режим Гармошки для списка: переключатель «Пишем / Учим» над словами. */
enum class PracticeMode(val label: String) {
    /** Списывание: слово видно, под ним пропись и мелкий перевод. */
    COPY("Пишем"),
    /** По памяти: видна подсказка, слово спрятано и звучит по плашке, открывается после СТОПа. */
    RECALL("Учим"),
}

/**
 * Стартовое положение переключателя, пока папа его не трогал: «Пишем», пока в списке есть слово, не написанное
 * сегодня ни разу; «Учим», когда каждое написано хоть раз (первые два столбика гармошки готовы).
 */
fun autoMode(items: List<WordItem>, attempts: List<Attempt>, today: (Long) -> Boolean = ::isToday): PracticeMode {
    val tasks = tasksOf(items)
    return if (tasks.isNotEmpty() && tasks.all { attemptsToday(attempts, it, today) >= 1 }) PracticeMode.RECALL else PracticeMode.COPY
}

/** Выбор папы хранится строкой вида «recall@2026-09-17»: на следующий день переключатель снова встаёт сам. */
fun encodeModeOverride(mode: PracticeMode, day: LocalDate): String = "${mode.name.lowercase()}@$day"

fun decodeModeOverride(value: String?, today: LocalDate): PracticeMode? {
    val (name, day) = value?.split('@')?.takeIf { it.size == 2 } ?: return null
    if (day != today.toString()) return null
    return PracticeMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** Режим, в котором реально пойдёт слово: выбор папы на сегодня, иначе автоматика. */
fun effectiveMode(override: PracticeMode?, items: List<WordItem>, attempts: List<Attempt>, today: (Long) -> Boolean = ::isToday): PracticeMode =
    override ?: autoMode(items, attempts, today)

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
