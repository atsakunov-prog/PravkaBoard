package ru.tsakunov.pravka.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Язык, на котором пишется слово. */
enum class Lang(val code: String) {
    EN("en"), RU("ru");

    companion object {
        fun of(code: String?): Lang = entries.firstOrNull { it.code == code } ?: EN
    }
}

/** Список слов (обычно одна страница словаря из учебника). */
@Entity(tableName = "word_lists")
data class WordList(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
)

/** Пара «английское слово — русский перевод» внутри списка. */
@Entity(
    tableName = "word_items",
    foreignKeys = [
        ForeignKey(
            entity = WordList::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("listId")],
)
data class WordItem(
    @PrimaryKey val id: String,
    val listId: String,
    val en: String,
    val ru: String,
    /** "word" или "phrase" */
    val kind: String,
    val position: Int,
)

/** Одна попытка: Боря написал слово, мы засекли время. */
@Entity(
    tableName = "attempts",
    indices = [Index("lang"), Index("ts"), Index("itemId")],
)
data class Attempt(
    @PrimaryKey val id: String,
    val ts: Long,
    /** "en" или "ru" */
    val lang: String,
    /** Само слово; null для записей с бумаги, где сохранилось только число букв. */
    val word: String?,
    val letters: Int,
    val ms: Long,
    /** "app" — засечено в приложении, "paper" — перенесено с бумаги, "manual" — введено вручную. */
    val source: String,
    val listId: String? = null,
    val itemId: String? = null,
) {
    val secPerLetter: Double get() = ms / 1000.0 / letters
    val langEnum: Lang get() = Lang.of(lang)

    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_PAPER = "paper"
        const val SOURCE_MANUAL = "manual"
    }
}

data class WordListWithCount(
    val id: String,
    val title: String,
    val createdAt: Long,
    val itemCount: Int,
    /** Сколько слов реально можно писать: непустые английские плюс непустые русские. */
    val taskCount: Int,
)

/** Рассказ, сочинённый моделью на словах урока. */
@Entity(
    tableName = "stories",
    foreignKeys = [
        ForeignKey(entity = WordList::class, parentColumns = ["id"], childColumns = ["listId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("listId")],
)
data class Story(
    @PrimaryKey val id: String,
    val listId: String,
    val title: String,
    val textEn: String,
    val textRu: String,
    val createdAt: Long,
)

/** Прохождение контрольной по уроку: с какой попытки и за сколько. */
@Entity(tableName = "quiz_runs", indices = [Index("listId"), Index("ts")])
data class QuizRun(
    @PrimaryKey val id: String,
    val listId: String,
    val ts: Long,
    /** Номер попытки, на которой урок пройден без ошибок (1 = с первого раза). */
    val attempts: Int,
    val durationMs: Long,
    val words: Int,
)

/** Домашнее задание: одна тетрадная работа, проверяется несколько раз. */
@Entity(tableName = "homeworks")
data class Homework(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Одна проверка домашки по фото. */
@Entity(
    tableName = "homework_checks",
    foreignKeys = [
        ForeignKey(entity = Homework::class, parentColumns = ["id"], childColumns = ["homeworkId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("homeworkId")],
)
data class HomeworkCheck(
    @PrimaryKey val id: String,
    val homeworkId: String,
    val ts: Long,
    /** 1 — первая проверка, 2 — после исправлений и т.д. */
    val attemptNo: Int,
    val correct: Int,
    val total: Int,
    /** HomeworkResult в JSON. */
    val resultJson: String,
)

/** Набор правил с одной страницы учебника, разобранный на уровни. */
@Entity(tableName = "grammar_sets")
data class GrammarSet(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    /** GrammarSetContent в JSON. */
    val contentJson: String,
)

/** Прогресс по уровню (правилу) набора. */
@Entity(
    tableName = "grammar_progress",
    primaryKeys = ["setId", "ruleIndex"],
    foreignKeys = [
        ForeignKey(entity = GrammarSet::class, parentColumns = ["id"], childColumns = ["setId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("setId")],
)
data class GrammarProgress(
    val setId: String,
    val ruleIndex: Int,
    val passed: Boolean,
    val bestStreak: Int,
    val correct: Int,
    val total: Int,
    val updatedAt: Long,
)

/** Текст для чтения на время: страницы книжки с фото. Рассказы Opus читаются из таблицы stories. */
@Entity(tableName = "reading_texts")
data class ReadingText(
    @PrimaryKey val id: String,
    val title: String,
    val textEn: String,
    val textRu: String,
    val words: Int,
    val createdAt: Long,
)

/** Одно чтение текста: время и число запинок. textId — из reading_texts или stories. */
@Entity(tableName = "reading_runs", indices = [Index("textId"), Index("ts")])
data class ReadingRun(
    @PrimaryKey val id: String,
    val textId: String,
    val ts: Long,
    val durationMs: Long,
    val stumbles: Int,
    val words: Int,
) {
    val wordsPerMinute: Double get() = words / (durationMs / 60_000.0)
}
