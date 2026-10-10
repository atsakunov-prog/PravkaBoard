// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.smarttouch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NextLetterModelTest {
    private val model = NextLetterModel.Builder().apply {
        addWord("привет", 200)
        addWord("привез", 120)
        addWord("пример", 150)
        addWord("прямо", 140)
        addWord("мир", 180)
        addWord("кот", 160)
        addWord("её", 190)
        addWord("кто-нибудь", 100)
    }.build()

    private fun p(word: String, letter: Char) = model.probability(model.context(word), letter.code)

    @Test fun `frequent continuation is most likely`() {
        assertTrue(p("приве", 'т') > p("приве", 'з'))
        assertTrue(p("приве", 'з') > p("приве", 'к'))
        assertTrue(p("приве", 'т') > 0.5f)
    }

    @Test fun `word start`() {
        assertTrue(p("", 'п') > p("", 'м'))
        assertTrue(p("", 'м') > p("", 'ы'))
        // words are split at non-letters, so "нибудь" starts a word
        assertTrue(p("", 'н') > p("", 'ы'))
    }

    @Test fun `case and yo are ignored`() {
        assertEquals(p("приве", 'т'), p("ПРИВЕ", 'Т'))
        assertEquals(p("е", 'ё'), p("е", 'е'))
        assertTrue(p("е", 'е') > 0.3f)
    }

    @Test fun `probabilities sum to about one`() {
        val letters = "приветзмоякнбудь" // all letters in the words
        for (word in listOf("", "п", "при", "приве", "кто", "xyz", "мирмирмирмир")) {
            val context = model.context(word)
            val sum = letters.sumOf { model.probability(context, it.code).toDouble() }
            assertTrue(sum in 0.9..1.01, "sum $sum for '$word'")
        }
    }

    @Test fun `unknown letters and non-letters get a small probability`() {
        assertTrue(p("приве", 'q') < p("приве", 'т') / 20)
        assertTrue(p("приве", '1') < p("приве", 'т') / 20)
        assertTrue(p("q", 'т') > 0f)
    }

    @Test fun `serialization keeps predictions`() {
        val out = ByteArrayOutputStream()
        model.writeTo(out)
        val read = NextLetterModel.readFrom(ByteArrayInputStream(out.toByteArray()))
        for (word in listOf("", "п", "приве", "ми", "кто")) {
            for (letter in "абвгдеёжзийклмнопрстуфхцчшщъыьэюяq") {
                assertEquals(model.probability(model.context(word), letter.code), read.probability(read.context(word), letter.code))
            }
        }
    }
}
