package ru.tsakunov.pravka.domain

import ru.tsakunov.pravka.data.ActivityLog
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.GrammarProgress
import ru.tsakunov.pravka.data.HomeworkCheck
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.QuizRun
import ru.tsakunov.pravka.data.ReadingRun
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Период общей статистики. days == null — за всё время. */
enum class Period(val label: String, val days: Int?) {
    WEEK("Неделя", 7), MONTH("Месяц", 30), ALL("Всё время", null)
}

/** Полуинтервал [from, to) в миллисекундах эпохи. */
data class Range(val from: Long, val to: Long) {
    fun has(ts: Long): Boolean = ts >= from && ts < to
}

/** Занятие в общей статистике времени. */
enum class StudyKind(val label: String) {
    WRITING("Гармошка"), WORDS("Слова"), GRAMMAR("Грамматика"), READING("Текст")
}

data class DayBucket(val day: LocalDate, val ms: Map<StudyKind, Long>) {
    val total: Long get() = ms.values.sum()
}

data class TimeStats(
    val totalMs: Long,
    val byKind: Map<StudyKind, Long>,
    val activeDays: Int,
    val byDay: List<DayBucket>,
)

data class WritingStats(
    val words: Int, val letters: Int, val totalMs: Long,
    val enAvg: Double?, val ruAvg: Double?, val enBest: Attempt?, val ruBest: Attempt?,
)

data class WordsStats(
    val quizRuns: Int, val quizWordsSaid: Int, val quizFirstTry: Int, val quizMs: Long,
    val teachRounds: Int, val teachWords: Int, val teachKnown: Int, val teachMs: Long,
    val learnCards: Int, val learnMs: Long,
) {
    val quizFirstTryPct: Int? get() = if (quizRuns == 0) null else (quizFirstTry * 100.0 / quizRuns).toInt()
    val teachKnownPct: Int? get() = if (teachWords == 0) null else (teachKnown * 100.0 / teachWords).toInt()
    val empty: Boolean get() = quizRuns == 0 && teachRounds == 0 && learnCards == 0
}

data class GrammarStats(
    val answers: Int, val correct: Int, val sessions: Int, val ms: Long,
    /** Уровни считаются за всё время: это состояние, а не событие. */
    val levelsPassed: Int, val levelsTotal: Int, val bestStreak: Int,
) {
    val correctPct: Int? get() = if (answers == 0) null else (correct * 100.0 / answers).toInt()
}

data class HomeworkStats(
    /** Домашек, впервые проверенных в периоде. */
    val homeworks: Int,
    /** Пунктов и ошибок в первых проверках этих домашек. */
    val firstItems: Int, val firstWrong: Int,
    val perfectFirst: Int,
    /** Домашек, доведённых до «всё верно» после исправлений. */
    val fixedLater: Int,
    val checks: Int,
) {
    val errorPct: Int? get() = if (firstItems == 0) null else (firstWrong * 100.0 / firstItems).toInt()
}

data class ReadingStats(
    val runs: Int, val words: Int, val ms: Long, val bestWpm: Double?, val avgWpm: Double?,
    val micRuns: Int, val sentences: Int, val readOk: Int, val transSentences: Int, val transOk: Int,
) {
    val readOkPct: Int? get() = if (sentences == 0) null else (readOk * 100.0 / sentences).toInt()
    val transOkPct: Int? get() = if (transSentences == 0) null else (transOk * 100.0 / transSentences).toInt()
}

data class Overview(
    val range: Range,
    val time: TimeStats,
    val writing: WritingStats,
    val words: WordsStats,
    val grammar: GrammarStats,
    val homework: HomeworkStats,
    val reading: ReadingStats,
)

object OverviewCalc {
    private fun zone(): ZoneId = ZoneId.systemDefault()
    private fun dayOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone()).toLocalDate()
    private fun startOf(day: LocalDate): Long = day.atStartOfDay(zone()).toInstant().toEpochMilli()

    /** Период, заканчивающийся сегодня включительно: неделя — сегодня и шесть дней до него. */
    fun currentRange(period: Period, now: Long = System.currentTimeMillis()): Range {
        val today = dayOf(now)
        val to = startOf(today.plusDays(1))
        val from = period.days?.let { startOf(today.minusDays((it - 1).toLong())) } ?: 0L
        return Range(from, to)
    }

    /** Такой же по длине период перед текущим; для «всё время» его нет. */
    fun previousRange(period: Period, now: Long = System.currentTimeMillis()): Range? {
        val days = period.days ?: return null
        val cur = currentRange(period, now)
        val today = dayOf(now)
        return Range(startOf(today.minusDays((2L * days - 1))), cur.from)
    }

    fun compute(
        range: Range,
        attempts: List<Attempt>,
        quizRuns: List<QuizRun>,
        activity: List<ActivityLog>,
        grammarProgress: List<GrammarProgress>,
        grammarLevelsTotal: Int,
        checks: List<HomeworkCheck>,
        readingRuns: List<ReadingRun>,
        chartDays: Int,
    ): Overview {
        val att = attempts.filter { range.has(it.ts) && it.ms > 0 && it.letters > 0 }
        val quiz = quizRuns.filter { range.has(it.ts) }
        val logs = activity.filter { range.has(it.ts) }
        val reads = readingRuns.filter { range.has(it.ts) }

        // ---- Время по дням и видам ----
        val events = ArrayList<Triple<Long, StudyKind, Long>>()
        att.forEach { events += Triple(it.ts, StudyKind.WRITING, it.ms) }
        quiz.forEach { events += Triple(it.ts, StudyKind.WORDS, it.durationMs) }
        reads.forEach { events += Triple(it.ts, StudyKind.READING, it.durationMs) }
        logs.forEach { events += Triple(it.ts, if (it.kind == ActivityLog.KIND_GRAMMAR) StudyKind.GRAMMAR else StudyKind.WORDS, it.durationMs) }
        val byKind = StudyKind.entries.associateWith { k -> events.filter { it.second == k }.sumOf { it.third } }
        val activeDays = events.map { dayOf(it.first) }.toSet().size
        val lastDay = dayOf(range.to - 1)
        val byDay = (chartDays - 1 downTo 0).map { back ->
            val day = lastDay.minusDays(back.toLong())
            val ms = StudyKind.entries.associateWith { k -> events.filter { it.second == k && dayOf(it.first) == day }.sumOf { it.third } }
            DayBucket(day, ms)
        }
        val time = TimeStats(events.sumOf { it.third }, byKind, activeDays, byDay)

        // ---- Гармошка ----
        val en = statsFor(Lang.EN, att)
        val ru = statsFor(Lang.RU, att)
        val writing = WritingStats(att.size, att.sumOf { it.letters }, att.sumOf { it.ms }, en.avg, ru.avg, en.best, ru.best)

        // ---- Слова ----
        val teach = logs.filter { it.kind == ActivityLog.KIND_TEACH }
        val learn = logs.filter { it.kind == ActivityLog.KIND_LEARN }
        val words = WordsStats(
            quizRuns = quiz.size, quizWordsSaid = quiz.sumOf { it.words }, quizFirstTry = quiz.count { it.attempts == 1 }, quizMs = quiz.sumOf { it.durationMs },
            teachRounds = teach.size, teachWords = teach.sumOf { it.total }, teachKnown = teach.sumOf { it.correct }, teachMs = teach.sumOf { it.durationMs },
            learnCards = learn.sumOf { it.total }, learnMs = learn.sumOf { it.durationMs },
        )

        // ---- Грамматика ----
        val gLogs = logs.filter { it.kind == ActivityLog.KIND_GRAMMAR }
        val grammar = GrammarStats(
            answers = gLogs.sumOf { it.total }, correct = gLogs.sumOf { it.correct }, sessions = gLogs.size, ms = gLogs.sumOf { it.durationMs },
            levelsPassed = grammarProgress.count { it.passed }, levelsTotal = grammarLevelsTotal,
            bestStreak = grammarProgress.maxOfOrNull { it.bestStreak } ?: 0,
        )

        // ---- Домашка: по работам, впервые проверенным в периоде ----
        val byHomework = checks.groupBy { it.homeworkId }
        val firstInRange = byHomework.values.mapNotNull { list ->
            val first = list.minByOrNull { it.attemptNo } ?: return@mapNotNull null
            if (range.has(first.ts)) first to list.maxByOrNull { it.attemptNo }!! else null
        }
        val homework = HomeworkStats(
            homeworks = firstInRange.size,
            firstItems = firstInRange.sumOf { it.first.total },
            firstWrong = firstInRange.sumOf { it.first.total - it.first.correct },
            perfectFirst = firstInRange.count { it.first.total > 0 && it.first.correct == it.first.total },
            fixedLater = firstInRange.count { (f, l) -> f.correct < f.total && l.total > 0 && l.correct == l.total },
            checks = checks.count { range.has(it.ts) },
        )

        // ---- Текст ----
        val mic = reads.filter { it.isMic }
        val wpm = reads.filter { it.wordsPerMinute > 0 }
        val reading = ReadingStats(
            runs = reads.size, words = reads.sumOf { it.words }, ms = reads.sumOf { it.durationMs },
            bestWpm = wpm.maxOfOrNull { it.wordsPerMinute },
            avgWpm = if (wpm.isEmpty()) null else wpm.sumOf { it.words } / (wpm.sumOf { it.readMs } / 60_000.0),
            micRuns = mic.size,
            sentences = mic.sumOf { it.sentences ?: 0 }, readOk = mic.sumOf { it.readOk ?: 0 },
            transSentences = mic.filter { it.transOk != null }.sumOf { it.sentences ?: 0 }, transOk = mic.sumOf { it.transOk ?: 0 },
        )

        return Overview(range, time, writing, words, grammar, homework, reading)
    }

    /**
     * Серия дней подряд с любым занятием, считая от сегодня; если сегодня ещё пусто, от вчера.
     */
    fun streakDays(days: Set<LocalDate>, today: LocalDate = LocalDate.now(zone())): Int {
        if (days.isEmpty()) return 0
        var cursor = if (today in days) today else today.minusDays(1)
        var n = 0
        while (cursor in days) { n++; cursor = cursor.minusDays(1) }
        return n
    }

    fun activeDays(attempts: List<Attempt>, quizRuns: List<QuizRun>, activity: List<ActivityLog>, readingRuns: List<ReadingRun>, checks: List<HomeworkCheck>): Set<LocalDate> {
        val out = HashSet<LocalDate>()
        attempts.forEach { out += dayOf(it.ts) }
        quizRuns.forEach { out += dayOf(it.ts) }
        activity.forEach { out += dayOf(it.ts) }
        readingRuns.forEach { out += dayOf(it.ts) }
        checks.forEach { out += dayOf(it.ts) }
        return out
    }

    /** Изменение к прошлому периоду в процентах; null, если сравнивать нечего. */
    fun deltaPct(current: Double, previous: Double): Int? {
        if (previous <= 0.0) return null
        return ((current / previous - 1) * 100).toInt()
    }
}
