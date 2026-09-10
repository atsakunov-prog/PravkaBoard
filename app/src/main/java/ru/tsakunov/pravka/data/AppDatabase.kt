package ru.tsakunov.pravka.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WordList::class, WordItem::class, Attempt::class, Story::class, QuizRun::class,
        Homework::class, HomeworkCheck::class, GrammarSet::class, GrammarProgress::class,
        ReadingText::class, ReadingRun::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wordListDao(): WordListDao
    abstract fun attemptDao(): AttemptDao
    abstract fun storyDao(): StoryDao
    abstract fun quizRunDao(): QuizRunDao
    abstract fun homeworkDao(): HomeworkDao
    abstract fun grammarDao(): GrammarDao
    abstract fun readingDao(): ReadingDao
    abstract fun storyListDao(): StoryListDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pravka.db")
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
