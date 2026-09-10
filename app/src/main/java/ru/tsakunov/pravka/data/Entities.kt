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
