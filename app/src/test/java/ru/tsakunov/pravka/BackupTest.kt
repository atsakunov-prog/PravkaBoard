package ru.tsakunov.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tsakunov.pravka.data.ActivityLog
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.GrammarProgress
import ru.tsakunov.pravka.data.GrammarSet
import ru.tsakunov.pravka.data.Homework
import ru.tsakunov.pravka.data.HomeworkCheck
import ru.tsakunov.pravka.data.QuizRun
import ru.tsakunov.pravka.data.ReadingRun
import ru.tsakunov.pravka.data.ReadingText
import ru.tsakunov.pravka.data.Story
import ru.tsakunov.pravka.data.Tombstone
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.data.WordList
import ru.tsakunov.pravka.domain.Backup
import ru.tsakunov.pravka.domain.Snapshot

class BackupTest {
    private val full = Snapshot(
        lists = listOf(WordList("l1", "Lesson 3", 1000, 2000)),
        items = listOf(WordItem("i1", "l1", "a hen", "курица", "word", 0, 2000), WordItem("i2", "l1", "to plant", "сажать", "phrase", 1, 0)),
        attempts = listOf(
            Attempt("a1", 3000, "en", "a hen", 4, 12_000, Attempt.SOURCE_APP, "l1", "i1"),
            Attempt("a2", 3100, "ru", null, 7, 20_000, Attempt.SOURCE_PAPER, null, null),
        ),
        stories = listOf(Story("s1", "l1", "The Hen", "A hen.", "Курица.", 4000)),
        quizRuns = listOf(QuizRun("q1", "l1", 5000, 2, 60_000, 8)),
        homeworks = listOf(Homework("h1", "Ex. 4", 6000, 6500)),
        homeworkChecks = listOf(HomeworkCheck("c1", "h1", 6500, 1, 5, 6, """{"title":"Ex. 4"}""")),
        grammarSets = listOf(GrammarSet("g1", "Plural", 7000, """{"rules":[]}""")),
        grammarProgress = listOf(GrammarProgress("g1", 0, true, 5, 9, 10, 7100)),
        readingTexts = listOf(ReadingText("t1", "Tea", "I like tea.", "Я люблю чай.", 3, 8000)),
        readingRuns = listOf(
            ReadingRun("r1", "t1", 8100, 15_000, 1, 3),
            ReadingRun("r2", "s1", 8200, 30_000, 0, 2, ReadingRun.MODE_MIC, 20_000, 1, 1, 0, """{"sentences":[]}"""),
        ),
        activity = listOf(ActivityLog("act1", 9000, ActivityLog.KIND_LEARN, "l1", 120_000, 10, 10)),
        tombstones = listOf(Tombstone("dead1", Tombstone.LIST, 9500)),
        exportedAt = 10_000,
        device = "test",
    )

    @Test
    fun roundTripKeepsEverything() {
        val parsed = Backup.parse(Backup.toJson(full, pretty = true))
        assertEquals(full, parsed)
    }

    @Test
    fun legacyVersionOneFileStillImports() {
        val v1 = """
            {"version":1,"exportedAt":1,
             "lists":[{"id":"l1","title":"Old","createdAt":5}],
             "items":[{"id":"i1","listId":"l1","en":"cat","ru":"кот","kind":"word","position":0}],
             "attempts":[
               {"id":"a1","ts":7,"lang":"en","word":"cat","letters":3,"ms":9000,"source":"app","listId":"l1","itemId":"i1"},
               {"id":"a2","ts":8,"lang":"ru","word":null,"letters":0,"ms":9000,"source":"paper","listId":null,"itemId":null},
               {"id":"a3","ts":9,"lang":"ru","word":"null","letters":4,"ms":9000,"source":"paper","listId":"null","itemId":null}
             ]}
        """.trimIndent()
        val s = Backup.parse(v1)
        assertEquals(1, s.lists.size)
        assertEquals(0L, s.lists[0].updatedAt)
        assertEquals(1, s.items.size)
        // Попытка без букв отброшена, слово "null" из старых копий читается как отсутствие слова.
        assertEquals(listOf("a1", "a3"), s.attempts.map { it.id })
        assertNull(s.attempts[1].word)
        assertNull(s.attempts[1].listId)
        assertTrue(s.tombstones.isEmpty())
    }

    @Test
    fun fingerprintIgnoresOrderTimeAndDevice() {
        val shuffled = full.copy(
            items = full.items.reversed(), attempts = full.attempts.reversed(), readingRuns = full.readingRuns.reversed(),
            exportedAt = 999, device = "other",
        )
        assertEquals(Backup.fingerprint(full), Backup.fingerprint(shuffled))
        assertNotEquals(Backup.fingerprint(full), Backup.fingerprint(full.copy(attempts = full.attempts.drop(1))))
    }

    @Test
    fun brokenRecordsAreSkippedNotFatal() {
        val text = """{"version":2,"lists":[{"title":"no id"}],"items":[{"id":"x"}],"grammarProgress":[{"setId":"g","ruleIndex":-1}],"tombstones":[{"kind":"list"}]}"""
        val s = Backup.parse(text)
        assertTrue(s.isEmpty)
    }
}
