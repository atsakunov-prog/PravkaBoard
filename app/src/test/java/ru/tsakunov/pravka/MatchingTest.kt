package ru.tsakunov.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tsakunov.pravka.domain.Matching

class MatchingTest {

    @Test
    fun exactAndArticleTolerant() {
        assertTrue(Matching.matches("a hen", listOf("hen")))
        assertTrue(Matching.matches("a hen", listOf("a hen")))
        assertTrue(Matching.matches("hen", listOf("the hen")))
        assertTrue(Matching.matches("to plant", listOf("plant")))
        assertTrue(Matching.matches("to plant", listOf("to plant.")))
        assertTrue(Matching.matches("work", listOf("Work")))
    }

    @Test
    fun fuzzyForRecognitionNoise() {
        assertTrue(Matching.matches("a goose", listOf("goose", "guess")))
        assertTrue(Matching.matches("wheat", listOf("wheet")))      // одна замена в слове из 5 букв
        assertTrue(Matching.matches("to plant", listOf("plants")))  // одна вставка
        assertFalse(Matching.matches("hen", listOf("hand")))         // коротким словам нужна точность
        assertFalse(Matching.matches("to do", listOf("today")))
        assertTrue(Matching.matches("warm", listOf("worm")))      // одна ошибка в слове из 4 букв прощается
        assertFalse(Matching.matches("warm", listOf("want", "farm house cat")))
    }

    @Test
    fun phrasesAcceptMostTokens() {
        assertTrue(Matching.matches("Who can help me?", listOf("who can help me")))
        assertTrue(Matching.matches("The seeds want water.", listOf("seeds want water")))
        assertTrue(Matching.matches("Here is the little red hen.", listOf("here is little red hen")))
        assertFalse(Matching.matches("Here is the little red hen.", listOf("hen")))
        assertTrue(Matching.isPhrase("Who can help me?"))
        assertFalse(Matching.isPhrase("to plant"))
    }

    @Test
    fun levenshtein() {
        assertEquals(0, Matching.levenshtein("abc", "abc"))
        assertEquals(1, Matching.levenshtein("hen", "hem"))
        assertEquals(2, Matching.levenshtein("kitten", "sittin"))
    }
}
