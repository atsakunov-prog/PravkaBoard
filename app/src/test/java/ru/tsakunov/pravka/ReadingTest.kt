package ru.tsakunov.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tsakunov.pravka.domain.LocalReading
import ru.tsakunov.pravka.domain.ReadingDetail
import ru.tsakunov.pravka.domain.SentenceResult
import ru.tsakunov.pravka.domain.Sentences

class ReadingTest {
    @Test
    fun splitsSentencesAndKeepsQuotes() {
        val text = "The Little Red Hen finds a seed. \"Who will help me plant it?\" she asks.\n\nThe Cat says no! The Duck says no. Nobody helps her…"
        val s = Sentences.split(text)
        assertEquals(
            listOf(
                "The Little Red Hen finds a seed.",
                "\"Who will help me plant it?\" she asks.",
                "The Cat says no!",
                "The Duck says no.",
                "Nobody helps her…",
            ),
            s,
        )
    }

    @Test
    fun splitJoinsLineBreaksInsideParagraph() {
        val s = Sentences.split("I am a hen.\nI live on a farm.\nIt is warm here.")
        assertEquals(3, s.size)
        assertEquals("I live on a farm.", s[1])
    }

    @Test
    fun localReadingVerdicts() {
        val sentence = "The little red hen finds a seed of wheat."
        assertEquals(SentenceResult.READ_OK, LocalReading.readingVerdict(sentence, "the little red hen finds a seed of wheat"))
        // одно слово не расслышано (hen → hand): всё ещё чисто, 8 слов из 9
        assertEquals(SentenceResult.READ_OK, LocalReading.readingVerdict(sentence, "the little red hand finds a seed of wheat"))
        // seed → sead: одна буква, допуск для слов от 4 букв; hen → hand не проходит (2 правки на 3 буквы)
        assertEquals(listOf("hen"), LocalReading.missedWords(sentence, "the little red hand finds a sead of wheat"))
        assertEquals(SentenceResult.READ_SLIPS, LocalReading.readingVerdict(sentence, "the little red hen finds"))
        assertEquals(SentenceResult.READ_WRONG, LocalReading.readingVerdict(sentence, "cat dog house"))
        assertEquals(SentenceResult.SKIPPED, LocalReading.readingVerdict(sentence, ""))
        assertEquals(listOf("wheat"), LocalReading.missedWords(sentence, "the little red hen finds a seed of"))
    }

    @Test
    fun detailRoundTripsThroughJson() {
        val d = ReadingDetail(
            sentences = listOf(
                SentenceResult("A hen.", "a hen", "курица", SentenceResult.READ_OK, SentenceResult.TR_OK, "", 1200),
                SentenceResult("A goose.", "a moose", "гусь", SentenceResult.READ_SLIPS, SentenceResult.TR_PARTIAL, "Не moose, а goose", 1500),
            ),
            praise = "Молодец", judged = true,
        )
        val back = ReadingDetail.fromJson(d.toJson())!!
        assertEquals(d, back)
        assertEquals(1, back.readOk)
        assertEquals(1, back.readSlips)
        assertEquals(1, back.transOk)
        assertTrue(back.translationKnown)
    }
}
