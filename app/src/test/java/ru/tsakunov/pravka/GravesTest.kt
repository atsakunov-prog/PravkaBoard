package ru.tsakunov.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tsakunov.pravka.data.Tombstone
import ru.tsakunov.pravka.domain.itemAlive
import ru.tsakunov.pravka.domain.newerGraves

class GravesTest {

    private fun grave(id: String, ts: Long) = Tombstone(id = id, kind = Tombstone.ITEM, ts = ts)
    private fun restore(id: String, ts: Long) = Tombstone(id = Tombstone.restoredId(id), kind = Tombstone.ITEM_RESTORED, ts = ts)
    private fun map(vararg t: Tombstone) = t.associateBy { it.id }

    @Test
    fun alive_withoutGrave_deadWithGrave() {
        assertTrue(itemAlive("x", emptyMap()))
        assertFalse(itemAlive("x", map(grave("x", 100))))
    }

    @Test
    fun restoreAfterGraveRevivesIt_reDeleteAfterRestoreKillsAgain() {
        assertTrue(itemAlive("x", map(grave("x", 100), restore("x", 200))))
        assertFalse(itemAlive("x", map(grave("x", 300), restore("x", 200))))
        // Возврат с тем же временем, что и могила, могилу не отменяет.
        assertFalse(itemAlive("x", map(grave("x", 200), restore("x", 200))))
    }

    @Test
    fun restoreOfAnotherWordDoesNotHelp() {
        assertFalse(itemAlive("x", map(grave("x", 100), restore("y", 200))))
    }

    @Test
    fun newerGraves_takesUnknownAndLaterOnly() {
        val local = map(grave("x", 100), restore("x", 200))
        val incoming = listOf(grave("x", 300), restore("x", 150), grave("z", 50))
        assertEquals(listOf("x", "z"), newerGraves(local, incoming).map { it.id })
    }
}
