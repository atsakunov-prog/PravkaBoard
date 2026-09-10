package ru.tsakunov.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.tsakunov.pravka.domain.IntakeKind
import ru.tsakunov.pravka.domain.IntakePart

class IntakeTest {
    @Test
    fun partsRoundTripThroughJson() {
        val parts = listOf(
            IntakePart(IntakeKind.VOCABULARY, listOf(1, 2), "vocabulary-abc"),
            IntakePart(IntakeKind.EXERCISE, listOf(3), "exercise-def", status = IntakePart.DONE, targetId = "hw-1", title = "Lesson 4, HA 2"),
            IntakePart(IntakeKind.READING, listOf(4, 5, 6), "reading-ghi", status = IntakePart.ERROR, error = "На фото не нашлось текста"),
        )
        val back = IntakePart.listFromJson(IntakePart.listToJson(parts))
        assertEquals(parts, back)
        assertEquals(1, back.count { it.done })
        assertEquals(1, back.count { it.failed })
        assertNull(back[0].targetId)
    }

    @Test
    fun unknownKindsAreDroppedAndBrokenJsonIsEmpty() {
        assertEquals(IntakeKind.GRAMMAR, IntakeKind.fromApi("grammar"))
        assertNull(IntakeKind.fromApi("other"))
        assertEquals(emptyList<IntakePart>(), IntakePart.listFromJson("not json"))
        assertEquals(emptyList<IntakePart>(), IntakePart.listFromJson("""[{"kind":"other","pages":[1],"customId":"x"}]"""))
    }
}
