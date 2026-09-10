package ru.tsakunov.pravka.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WordListDao {
    @Query(
        """
        SELECT l.id, l.title, l.createdAt,
               (SELECT COUNT(*) FROM word_items i WHERE i.listId = l.id) AS itemCount,
               (SELECT COUNT(*) FROM word_items i WHERE i.listId = l.id AND TRIM(i.en) != '') +
               (SELECT COUNT(*) FROM word_items i WHERE i.listId = l.id AND TRIM(i.ru) != '') AS taskCount
        FROM word_lists l
        ORDER BY l.createdAt DESC
        """,
    )
    fun observeLists(): Flow<List<WordListWithCount>>

    @Query("SELECT * FROM word_lists WHERE id = :id")
    fun observeList(id: String): Flow<WordList?>

    @Query("SELECT * FROM word_lists WHERE id = :id")
    suspend fun getList(id: String): WordList?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertList(list: WordList)

    @Update
    suspend fun updateList(list: WordList)

    @Query("DELETE FROM word_lists WHERE id = :id")
    suspend fun deleteList(id: String)

    @Query("SELECT * FROM word_items WHERE listId = :listId ORDER BY position ASC")
    fun observeItems(listId: String): Flow<List<WordItem>>

    @Query("SELECT * FROM word_items WHERE listId = :listId ORDER BY position ASC")
    suspend fun getItems(listId: String): List<WordItem>

    @Query("SELECT * FROM word_items WHERE id = :id")
    suspend fun getItem(id: String): WordItem?

    @Query("SELECT COALESCE(MAX(position), -1) FROM word_items WHERE listId = :listId")
    suspend fun maxPosition(listId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<WordItem>)

    @Update
    suspend fun updateItem(item: WordItem)

    @Delete
    suspend fun deleteItem(item: WordItem)

    @Query("SELECT * FROM word_items ORDER BY position ASC")
    suspend fun allItems(): List<WordItem>

    @Query("SELECT * FROM word_lists ORDER BY createdAt DESC")
    suspend fun allLists(): List<WordList>

    @Query("DELETE FROM word_lists")
    suspend fun clearLists()
}

@Dao
interface AttemptDao {
    @Query("SELECT * FROM attempts ORDER BY ts ASC")
    fun observeAll(): Flow<List<Attempt>>

    @Query("SELECT * FROM attempts ORDER BY ts ASC")
    suspend fun all(): List<Attempt>

    @Query("SELECT COUNT(*) FROM attempts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attempt: Attempt)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attempts: List<Attempt>)

    @Query("DELETE FROM attempts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM attempts")
    suspend fun clear()
}

@Dao
interface StoryDao {
    @Query("SELECT * FROM stories WHERE listId = :listId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(listId: String): Flow<Story?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(story: Story)

    @Query("DELETE FROM stories WHERE listId = :listId")
    suspend fun deleteForList(listId: String)
}

@Dao
interface QuizRunDao {
    @Query("SELECT * FROM quiz_runs WHERE listId = :listId ORDER BY ts DESC")
    fun observeForList(listId: String): Flow<List<QuizRun>>

    @Query("SELECT * FROM quiz_runs ORDER BY ts DESC")
    fun observeAll(): Flow<List<QuizRun>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: QuizRun)
}

@Dao
interface HomeworkDao {
    @Query("SELECT * FROM homeworks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Homework>>

    @Query("SELECT * FROM homeworks WHERE id = :id")
    fun observe(id: String): Flow<Homework?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(homework: Homework)

    @Query("UPDATE homeworks SET updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: String, ts: Long)

    @Query("DELETE FROM homeworks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM homework_checks WHERE homeworkId = :homeworkId ORDER BY attemptNo ASC")
    fun observeChecks(homeworkId: String): Flow<List<HomeworkCheck>>

    @Query("SELECT * FROM homework_checks ORDER BY ts DESC")
    fun observeAllChecks(): Flow<List<HomeworkCheck>>

    @Query("SELECT COALESCE(MAX(attemptNo), 0) FROM homework_checks WHERE homeworkId = :homeworkId")
    suspend fun lastAttempt(homeworkId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheck(check: HomeworkCheck)
}

@Dao
interface GrammarDao {
    @Query("SELECT * FROM grammar_sets ORDER BY createdAt DESC")
    fun observeSets(): Flow<List<GrammarSet>>

    @Query("SELECT * FROM grammar_sets WHERE id = :id")
    fun observeSet(id: String): Flow<GrammarSet?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSet(set: GrammarSet)

    @Query("DELETE FROM grammar_sets WHERE id = :id")
    suspend fun deleteSet(id: String)

    @Query("SELECT * FROM grammar_progress WHERE setId = :setId")
    fun observeProgress(setId: String): Flow<List<GrammarProgress>>

    @Query("SELECT * FROM grammar_progress")
    fun observeAllProgress(): Flow<List<GrammarProgress>>

    @Query("SELECT * FROM grammar_progress WHERE setId = :setId AND ruleIndex = :ruleIndex")
    suspend fun getProgress(setId: String, ruleIndex: Int): GrammarProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: GrammarProgress)
}

@Dao
interface ReadingDao {
    @Query("SELECT * FROM reading_texts ORDER BY createdAt DESC")
    fun observeTexts(): Flow<List<ReadingText>>

    @Query("SELECT * FROM reading_texts WHERE id = :id")
    fun observeText(id: String): Flow<ReadingText?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertText(text: ReadingText)

    @Query("DELETE FROM reading_texts WHERE id = :id")
    suspend fun deleteText(id: String)

    @Query("SELECT * FROM reading_runs ORDER BY ts ASC")
    fun observeAllRuns(): Flow<List<ReadingRun>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: ReadingRun)

    @Query("DELETE FROM reading_runs WHERE id = :id")
    suspend fun deleteRun(id: String)
}

@Dao
interface StoryListDao {
    @Query("SELECT * FROM stories ORDER BY createdAt DESC")
    fun observeAllStories(): Flow<List<Story>>

    @Query("SELECT * FROM stories WHERE id = :id")
    fun observeStoryById(id: String): Flow<Story?>
}
