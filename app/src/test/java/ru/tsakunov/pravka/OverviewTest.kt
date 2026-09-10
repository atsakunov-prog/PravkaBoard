package ru.tsakunov.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.tsakunov.pravka.data.ActivityLog
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.GrammarProgress
import ru.tsakunov.pravka.data.HomeworkCheck
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.QuizRun
import ru.tsakunov.pravka.data.ReadingRun
import ru.tsakunov.pravka.domain.OverviewCalc
import ru.tsakunov.pravka.domain.Period
import ru.tsakunov.pravka.domain.StudyKind
import java.time.LocalDate
import java.time.ZoneId

class OverviewTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun weekRangeCoversTodayAndSixDaysBefore() {
        val now = at(today, 15)
        val r = OverviewCalc.currentRange(Period.WEEK, now)
        assertEquals(at(today.minusDays(6), 0), r.from)
        assertEquals(at(today.plusDays(1), 0), r.to)
        val prev = OverviewCalc.previousRange(Period.WEEK, now)!!
        assertEquals(r.from, prev.to)
        assertEquals(at(today.minusDays(13), 0), prev.from)
        assertNull(OverviewCalc.previousRange(Period.ALL, now))
        assertEquals(0L, OverviewCalc.currentRange(Period.ALL, now).from)
    }

    @Test
    fun computeAggregatesEverySection() {
        val t = at(today, 12)
        val old = at(today.minusDays(20), 12) // вне недели
        val attempts = listOf(
            Attempt(id = "a1", ts = t, lang = Lang.EN.code, word = "a hen", letters = 4, ms = 20_000, source = Attempt.SOURCE_APP),
            Attempt(id = "a2", ts = t + 1, lang = Lang.RU.code, word = "курица", letters = 6, ms = 30_000, source = Attempt.SOURCE_APP),
            Attempt(id = "a3", ts = old, lang = Lang.EN.code, word = "warm", letters = 4, ms = 40_000, source = Attempt.SOURCE_APP),
        )
        val quiz = listOf(QuizRun("q1", "l1", t, attempts = 1, durationMs = 60_000, words = 11))
        val logs = listOf(
            ActivityLog("g1", t, ActivityLog.KIND_TEACH, "l1", 120_000, total = 10, correct = 7),
            ActivityLog("g2", t, ActivityLog.KIND_GRAMMAR, "s1", 90_000, total = 10, correct = 8),
            ActivityLog("g3", t, ActivityLog.KIND_LEARN, "l1", 30_000, total = 11, correct = 11),
        )
        val progress = listOf(
            GrammarProgress("s1", 0, passed = true, bestStreak = 6, correct = 12, total = 14, updatedAt = t),
            GrammarProgress("s1", 1, passed = false, bestStreak = 3, correct = 4, total = 8, updatedAt = t),
        )
        val checks = listOf(
            HomeworkCheck("c1", "hwA", t, attemptNo = 1, correct = 8, total = 10, resultJson = "{}"),
            HomeworkCheck("c2", "hwA", t + 1, attemptNo = 2, correct = 10, total = 10, resultJson = "{}"),
            HomeworkCheck("c3", "hwB", t, attemptNo = 1, correct = 5, total = 5, resultJson = "{}"),
            HomeworkCheck("c4", "hwOld", old, attemptNo = 1, correct = 1, total = 5, resultJson = "{}"),
        )
        val reads = listOf(
            ReadingRun("r1", "t1", t, durationMs = 60_000, stumbles = 1, words = 120),
            ReadingRun(
                "r2", "t1", t + 1, durationMs = 90_000, stumbles = 0, words = 60,
                mode = ReadingRun.MODE_MIC, readingMs = 30_000, sentences = 6, readOk = 5, transOk = 4, detailJson = "{}",
            ),
        )
        val o = OverviewCalc.compute(OverviewCalc.currentRange(Period.WEEK, t), attempts, quiz, logs, progress, grammarLevelsTotal = 3, checks, reads, chartDays = 7)

        // Время: 50 с письма + 60 с контроши + 150 с карточек + 90 с грамматики + 150 с чтения
        assertEquals(50_000L, o.time.byKind[StudyKind.WRITING])
        assertEquals(210_000L, o.time.byKind[StudyKind.WORDS])
        assertEquals(90_000L, o.time.byKind[StudyKind.GRAMMAR])
        assertEquals(150_000L, o.time.byKind[StudyKind.READING])
        assertEquals(500_000L, o.time.totalMs)
        assertEquals(1, o.time.activeDays)
        assertEquals(7, o.time.byDay.size)
        assertEquals(500_000L, o.time.byDay.last().total)

        assertEquals(2, o.writing.words)
        assertEquals(10, o.writing.letters)
        assertEquals(5.0, o.writing.enAvg!!, 1e-9)

        assertEquals(11, o.words.quizWordsSaid)
        assertEquals(100, o.words.quizFirstTryPct)
        assertEquals(70, o.words.teachKnownPct)
        assertEquals(11, o.words.learnCards)

        assertEquals(80, o.grammar.correctPct)
        assertEquals(1, o.grammar.levelsPassed)
        assertEquals(3, o.grammar.levelsTotal)
        assertEquals(6, o.grammar.bestStreak)

        assertEquals(2, o.homework.homeworks)
        assertEquals(15, o.homework.firstItems)
        assertEquals(2, o.homework.firstWrong)
        assertEquals(13, o.homework.errorPct)
        assertEquals(1, o.homework.perfectFirst)
        assertEquals(1, o.homework.fixedLater)
        assertEquals(3, o.homework.checks)

        assertEquals(2, o.reading.runs)
        assertEquals(120.0, o.reading.bestWpm!!, 1e-9)
        assertEquals(120.0, o.reading.avgWpm!!, 1e-9)
        assertEquals(1, o.reading.micRuns)
        assertEquals(83, o.reading.readOkPct)
        assertEquals(66, o.reading.transOkPct)
    }

    @Test
    fun streakCountsBackFromTodayOrYesterday() {
        assertEquals(3, OverviewCalc.streakDays(setOf(today, today.minusDays(1), today.minusDays(2)), today))
        assertEquals(2, OverviewCalc.streakDays(setOf(today.minusDays(1), today.minusDays(2)), today))
        assertEquals(0, OverviewCalc.streakDays(setOf(today.minusDays(2)), today))
        assertEquals(0, OverviewCalc.streakDays(emptySet(), today))
        assertEquals(25, OverviewCalc.deltaPct(125.0, 100.0))
        assertNull(OverviewCalc.deltaPct(10.0, 0.0))
    }
}
