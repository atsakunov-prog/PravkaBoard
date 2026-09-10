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
