package ru.tsakunov.pravka.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WordList::class, WordItem::class, Attempt::class, Story::class, QuizRun::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wordListDao(): WordListDao
    abstract fun attemptDao(): AttemptDao
    abstract fun storyDao(): StoryDao
    abstract fun quizRunDao(): QuizRunDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pravka.db")
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
