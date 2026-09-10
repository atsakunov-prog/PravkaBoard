package ru.tsakunov.pravka.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import ru.tsakunov.pravka.domain.GrammarSetContent
import ru.tsakunov.pravka.domain.HomeworkResult
import ru.tsakunov.pravka.domain.countWords
import java.util.UUID

class Repository(
    private val db: AppDatabase,
    private val settings: Settings,
) {
    private val lists get() = db.wordListDao()
    private val attempts get() = db.attemptDao()
    private val stories get() = db.storyDao()
    private val quizRuns get() = db.quizRunDao()
    private val homeworks get() = db.homeworkDao()
    private val grammar get() = db.grammarDao()
    private val reading get() = db.readingDao()
    private val storyList get() = db.storyListDao()

    // ---- Наблюдение ----
    fun observeLists(): Flow<List<WordListWithCount>> = lists.observeLists()
    fun observeList(id: String): Flow<WordList?> = lists.observeList(id)
    fun observeItems(listId: String): Flow<List<WordItem>> = lists.observeItems(listId)
    fun observeAttempts(): Flow<List<Attempt>> = attempts.observeAll()

    // ---- Посев ----
    suspend fun seedIfNeeded() {
        if (settings.seedVersion >= Seed.VERSION) return
        // Идентификаторы посева стабильны, REPLACE лишь дополняет и обновляет бумажные записи.
        attempts.insertAll(Seed.attempts())
        settings.seedVersion = Seed.VERSION
    }

    // ---- Списки ----
    suspend fun createList(title: String, items: List<Pair<String, String>>, kinds: List<String>? = null): WordList {
        val list = WordList(id = newId(), title = title.trim().ifBlank { "Список слов" }, createdAt = System.currentTimeMillis())
        val rows = items.mapIndexedNotNull { idx, (en, ru) ->
            val e = en.trim()
            val r = ru.trim()
            if (e.isEmpty() && r.isEmpty()) null
            else WordItem(id = newId(), listId = list.id, en = e, ru = r, kind = kinds?.getOrNull(idx) ?: "word", position = idx)
        }
        db.withTransaction {
            lists.insertList(list)
            lists.insertItems(rows)
        }
        return list
    }

    suspend fun renameList(id: String, title: String) {
        val l = lists.getList(id) ?: return
        lists.updateList(l.copy(title = title.trim().ifBlank { l.title }))
    }

    suspend fun deleteList(id: String) = lists.deleteList(id)

    suspend fun getItems(listId: String): List<WordItem> = lists.getItems(listId)
    suspend fun getItem(id: String): WordItem? = lists.getItem(id)

    suspend fun addItem(listId: String, en: String, ru: String): WordItem {
        val item = WordItem(
            id = newId(), listId = listId, en = en.trim(), ru = ru.trim(), kind = "word",
            position = lists.maxPosition(listId) + 1,
        )
        lists.insertItems(listOf(item))
        return item
    }

    suspend fun updateItem(item: WordItem, en: String, ru: String) =
        lists.updateItem(item.copy(en = en.trim(), ru = ru.trim()))

    suspend fun deleteItem(item: WordItem) = lists.deleteItem(item)

    // ---- Попытки ----
    suspend fun addAttempt(
        lang: Lang,
        word: String?,
        letters: Int,
        ms: Long,
        source: String = Attempt.SOURCE_APP,
        listId: String? = null,
        itemId: String? = null,
        ts: Long = System.currentTimeMillis(),
    ): Attempt {
        val a = Attempt(
            id = newId(), ts = ts, lang = lang.code, word = word?.trim()?.ifBlank { null },
            letters = letters, ms = ms, source = source, listId = listId, itemId = itemId,
        )
        attempts.insert(a)
        return a
    }

    suspend fun deleteAttempt(id: String) = attempts.delete(id)

    // ---- Рассказы и контрольные ----
    fun observeStory(listId: String): Flow<Story?> = stories.observeLatest(listId)

    suspend fun saveStory(listId: String, title: String, textEn: String, textRu: String): Story {
        val story = Story(id = newId(), listId = listId, title = title, textEn = textEn, textRu = textRu, createdAt = System.currentTimeMillis())
        stories.insert(story)
        return story
    }

    fun observeQuizRuns(listId: String): Flow<List<QuizRun>> = quizRuns.observeForList(listId)
    fun observeAllQuizRuns(): Flow<List<QuizRun>> = quizRuns.observeAll()

    suspend fun addQuizRun(listId: String, attempts: Int, durationMs: Long, words: Int): QuizRun {
        val run = QuizRun(id = newId(), listId = listId, ts = System.currentTimeMillis(), attempts = attempts, durationMs = durationMs, words = words)
        quizRuns.insert(run)
        return run
    }

    // ---- Домашка ----
    fun observeHomeworks(): Flow<List<Homework>> = homeworks.observeAll()
    fun observeHomework(id: String): Flow<Homework?> = homeworks.observe(id)
    fun observeHomeworkChecks(id: String): Flow<List<HomeworkCheck>> = homeworks.observeChecks(id)
    fun observeAllHomeworkChecks(): Flow<List<HomeworkCheck>> = homeworks.observeAllChecks()

    /** Сохраняет проверку; если homeworkId == null, создаёт новую домашку. Возвращает её id. */
    suspend fun saveHomeworkCheck(homeworkId: String?, result: HomeworkResult): String {
        val now = System.currentTimeMillis()
        val id = homeworkId ?: newId()
        db.withTransaction {
            if (homeworkId == null) homeworks.insert(Homework(id = id, title = result.title, createdAt = now, updatedAt = now))
            else homeworks.touch(id, now)
            val attempt = homeworks.lastAttempt(id) + 1
            homeworks.insertCheck(
                HomeworkCheck(id = newId(), homeworkId = id, ts = now, attemptNo = attempt, correct = result.correct, total = result.total, resultJson = result.toJson()),
            )
        }
        return id
    }

    suspend fun deleteHomework(id: String) = homeworks.delete(id)

    // ---- Грамматика ----
    fun observeGrammarSets(): Flow<List<GrammarSet>> = grammar.observeSets()
    fun observeGrammarSet(id: String): Flow<GrammarSet?> = grammar.observeSet(id)
    fun observeGrammarProgress(setId: String): Flow<List<GrammarProgress>> = grammar.observeProgress(setId)
    fun observeAllGrammarProgress(): Flow<List<GrammarProgress>> = grammar.observeAllProgress()

    suspend fun saveGrammarSet(content: GrammarSetContent): GrammarSet {
        val set = GrammarSet(id = newId(), title = content.title, createdAt = System.currentTimeMillis(), contentJson = content.toJson())
        grammar.insertSet(set)
        return set
    }

    suspend fun deleteGrammarSet(id: String) = grammar.deleteSet(id)

    /** Записывает ответ по карточке уровня; возвращает обновлённый прогресс (passed выставляется при серии из STREAK_TO_PASS). */
    suspend fun recordGrammarAnswer(setId: String, ruleIndex: Int, correct: Boolean, currentStreak: Int): GrammarProgress = db.withTransaction {
        val prev = grammar.getProgress(setId, ruleIndex)
        val streak = if (correct) currentStreak + 1 else 0
        val p = GrammarProgress(
            setId = setId, ruleIndex = ruleIndex,
            passed = (prev?.passed ?: false) || streak >= GrammarSetContent.STREAK_TO_PASS,
            bestStreak = maxOf(prev?.bestStreak ?: 0, streak),
            correct = (prev?.correct ?: 0) + if (correct) 1 else 0,
            total = (prev?.total ?: 0) + 1,
            updatedAt = System.currentTimeMillis(),
        )
        grammar.upsertProgress(p)
        p
    }

    // ---- Чтение ----
    fun observeReadingTexts(): Flow<List<ReadingText>> = reading.observeTexts()
    fun observeReadingText(id: String): Flow<ReadingText?> = reading.observeText(id)
    fun observeReadingRuns(): Flow<List<ReadingRun>> = reading.observeAllRuns()
    fun observeAllStories(): Flow<List<Story>> = storyList.observeAllStories()
    suspend fun deleteStory(id: String) = db.withTransaction { reading.deleteRunsForText(id); stories.deleteById(id) }
    fun observeStoryById(id: String): Flow<Story?> = storyList.observeStoryById(id)

    suspend fun saveReadingText(title: String, textEn: String, textRu: String): ReadingText {
        val t = ReadingText(id = newId(), title = title, textEn = textEn, textRu = textRu, words = countWords(textEn), createdAt = System.currentTimeMillis())
        reading.insertText(t)
        return t
    }

    /** Текст удаляется вместе со своими чтениями: без текста им негде показываться. */
    suspend fun deleteReadingText(id: String) = db.withTransaction { reading.deleteRunsForText(id); reading.deleteText(id) }

    suspend fun addReadingRun(textId: String, durationMs: Long, stumbles: Int, words: Int): ReadingRun {
        val run = ReadingRun(id = newId(), textId = textId, ts = System.currentTimeMillis(), durationMs = durationMs, stumbles = stumbles, words = words)
        reading.insertRun(run)
        return run
    }

    suspend fun deleteReadingRun(id: String) = reading.deleteRun(id)

    // ---- Резервная копия ----
    suspend fun exportJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("lists", JSONArray().also { arr ->
            lists.allLists().forEach { l ->
                arr.put(JSONObject().put("id", l.id).put("title", l.title).put("createdAt", l.createdAt))
            }
        })
        root.put("items", JSONArray().also { arr ->
            lists.allItems().forEach { i ->
                arr.put(
                    JSONObject().put("id", i.id).put("listId", i.listId).put("en", i.en).put("ru", i.ru)
                        .put("kind", i.kind).put("position", i.position),
                )
            }
        })
        root.put("attempts", JSONArray().also { arr ->
            attempts.all().forEach { a ->
                arr.put(
                    JSONObject().put("id", a.id).put("ts", a.ts).put("lang", a.lang)
                        .put("word", a.word ?: JSONObject.NULL).put("letters", a.letters).put("ms", a.ms)
                        .put("source", a.source).put("listId", a.listId ?: JSONObject.NULL)
                        .put("itemId", a.itemId ?: JSONObject.NULL),
                )
            }
        })
        return root.toString(2)
    }

    /** Импорт объединяет данные: записи с теми же id заменяются, новые добавляются. */
    suspend fun importJson(text: String): Int {
        val root = JSONObject(text)
        val newLists = ArrayList<WordList>()
        val newItems = ArrayList<WordItem>()
        val newAttempts = ArrayList<Attempt>()
        root.optJSONArray("lists")?.let { arr ->
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                newLists += WordList(o.getString("id"), o.optString("title", "Список"), o.optLong("createdAt", System.currentTimeMillis()))
            }
        }
        root.optJSONArray("items")?.let { arr ->
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                newItems += WordItem(
                    o.getString("id"), o.getString("listId"), o.optString("en", ""), o.optString("ru", ""),
                    o.optString("kind", "word"), o.optInt("position", k),
                )
            }
        }
        root.optJSONArray("attempts")?.let { arr ->
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val letters = o.optInt("letters", 0)
                val ms = o.optLong("ms", 0)
                if (letters <= 0 || ms <= 0) continue
                newAttempts += Attempt(
                    id = o.optString("id").ifBlank { newId() }, ts = o.optLong("ts", System.currentTimeMillis()),
                    lang = o.optString("lang", "en"), word = o.optString("word").takeIf { it.isNotBlank() && it != "null" },
                    letters = letters, ms = ms, source = o.optString("source", Attempt.SOURCE_MANUAL),
                    listId = o.optString("listId").takeIf { it.isNotBlank() && it != "null" },
                    itemId = o.optString("itemId").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }
        db.withTransaction {
            newLists.forEach { lists.insertList(it) }
            lists.insertItems(newItems)
            attempts.insertAll(newAttempts)
        }
        return newAttempts.size
    }

    suspend fun resetAll() {
        db.withTransaction {
            attempts.clear()
            lists.clearLists()
            attempts.insertAll(Seed.attempts())
        }
    }

    private fun newId() = UUID.randomUUID().toString()
}
