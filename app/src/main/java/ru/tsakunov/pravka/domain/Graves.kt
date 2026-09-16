package ru.tsakunov.pravka.domain

import ru.tsakunov.pravka.data.Tombstone

/**
 * Надгробия сливаются по времени, а не только по наличию: повторное удаление того же слова после возврата
 * приходит с более поздним временем и должно перебить прежнюю запись. Чужая запись принимается, если её
 * ещё нет или она новее местной.
 */
fun newerGraves(local: Map<String, Tombstone>, incoming: List<Tombstone>): List<Tombstone> =
    incoming.filter { t -> local[t.id]?.let { t.ts > it.ts } ?: true }

/**
 * Живо ли слово с точки зрения надгробий: могилы нет, или её отменил более поздний возврат
 * (запись ITEM_RESTORED с id restoredId). Время правки самого слова здесь не участвует: его двигает любая
 * перестановка в списке, и она не должна воскрешать слово, удалённое на другом устройстве.
 */
fun itemAlive(itemId: String, graves: Map<String, Tombstone>): Boolean {
    val grave = graves[itemId] ?: return true
    val restore = graves[Tombstone.restoredId(itemId)] ?: return false
    return restore.ts > grave.ts
}
