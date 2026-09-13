package ru.tsakunov.pravka.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import ru.tsakunov.pravka.domain.Backup
import ru.tsakunov.pravka.domain.GrammarSetContent
import ru.tsakunov.pravka.domain.HomeworkResult
import ru.tsakunov.pravka.domain.ReadingDetail
import ru.tsakunov.pravka.domain.Snapshot
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
    private val intake get() = db.intakeDao()
    private val activity get() = db.activityDao()
    private val tombstones get() = db.tombstoneDao()

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
        val now = System.currentTimeMillis()
        val list = WordList(id = newId(), title = title.trim().ifBlank { "Список слов" }, createdAt = now, updatedAt = now)
        val rows = items.mapIndexedNotNull { idx, (en, ru) ->
            val e = en.trim()
            val r = ru.trim()
            if (e.isEmpty() && r.isEmpty()) null
            else WordItem(id = newId(), listId = list.id, en = e, ru = r, kind = kinds?.getOrNull(idx) ?: "word", position = idx, updatedAt = now)
        }
        db.withTransaction {
            lists.insertList(list)
            lists.insertItems(rows)
        }
        return list
    }

    suspend fun renameList(id: String, title: String) {
        val l = lists.getList(id) ?: return
        lists.updateList(l.copy(title = title.trim().ifBlank { l.title }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteList(id: String) = db.withTransaction { lists.deleteList(id); bury(Tombstone.LIST, id) }

    suspend fun getItems(listId: String): List<WordItem> = lists.getItems(listId)
    suspend fun getItem(id: String): WordItem? = lists.getItem(id)

    suspend fun addItem(listId: String, en: String, ru: String): WordItem {
        val item = WordItem(
            id = newId(), listId = listId, en = en.trim(), ru = ru.trim(), kind = "word",
            position = lists.maxPosition(listId) + 1, updatedAt = System.currentTimeMillis(),
        )
        lists.insertItems(listOf(item))
        return item
    }

    suspend fun updateItem(item: WordItem, en: String, ru: String) =
        lists.updateItem(item.copy(en = en.trim(), ru = ru.trim(), updatedAt = System.currentTimeMillis()))

    suspend fun deleteItem(item: WordItem) = db.withTransaction { lists.deleteItem(item); bury(Tombstone.ITEM, item.id) }

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

    suspend fun deleteAttempt(id: String) = db.withTransaction { attempts.delete(id); bury(Tombstone.ATTEMPT, id) }

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

    suspend fun deleteHomework(id: String) = db.withTransaction { homeworks.delete(id); bury(Tombstone.HOMEWORK, id) }

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

    suspend fun deleteGrammarSet(id: String) = db.withTransaction { grammar.deleteSet(id); bury(Tombstone.GRAMMAR_SET, id) }

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
    suspend fun deleteStory(id: String) = db.withTransaction { reading.deleteRunsForText(id); stories.deleteById(id); bury(Tombstone.STORY, id) }
    fun observeStoryById(id: String): Flow<Story?> = storyList.observeStoryById(id)

    suspend fun saveReadingText(title: String, textEn: String, textRu: String): ReadingText {
        val t = ReadingText(id = newId(), title = title, textEn = textEn, textRu = textRu, words = countWords(textEn), createdAt = System.currentTimeMillis())
        reading.insertText(t)
        return t
    }

    /** Текст удаляется вместе со своими чтениями: без текста им негде показываться. */
    suspend fun deleteReadingText(id: String) = db.withTransaction { reading.deleteRunsForText(id); reading.deleteText(id); bury(Tombstone.READING_TEXT, id) }

    suspend fun addReadingRun(textId: String, durationMs: Long, stumbles: Int, words: Int): ReadingRun {
        val run = ReadingRun(id = newId(), textId = textId, ts = System.currentTimeMillis(), durationMs = durationMs, stumbles = stumbles, words = words)
        reading.insertRun(run)
        return run
    }

    /** Чтение с микрофоном: общее время сеанса, чистое время чтения и разбор по предложениям. */
    suspend fun addMicReadingRun(textId: String, durationMs: Long, readingMs: Long, words: Int, detail: ReadingDetail): ReadingRun {
        val run = ReadingRun(
            id = newId(), textId = textId, ts = System.currentTimeMillis(), durationMs = durationMs, stumbles = 0, words = words,
            mode = ReadingRun.MODE_MIC, readingMs = readingMs, sentences = detail.total, readOk = detail.readOk,
            transOk = if (detail.translationKnown) detail.transOk else null, detailJson = detail.toJson(),
        )
        reading.insertRun(run)
        return run
    }

    suspend fun deleteReadingRun(id: String) = db.withTransaction { reading.deleteRun(id); bury(Tombstone.READING_RUN, id) }

    // ---- Резервная копия и синхронизация ----
    /** Слепок всего, что стоит переносить между устройствами. Разборы всей домашки остаются на своём телефоне. */
    suspend fun snapshot(): Snapshot = Snapshot(
        lists = lists.allLists(), items = lists.allItems(), attempts = attempts.all(),
        stories = stories.all(), quizRuns = quizRuns.all(),
        homeworks = homeworks.all(), homeworkChecks = homeworks.allChecks(),
        grammarSets = grammar.allSets(), grammarProgress = grammar.allProgress(),
        readingTexts = reading.allTexts(), readingRuns = reading.allRuns(),
        activity = activity.all(), tombstones = tombstones.all(),
        exportedAt = System.currentTimeMillis(),
    )

    suspend fun exportJson(): String = Backup.toJson(snapshot(), pretty = true)

    /** Импорт из файла: то же слияние, что и в синхронизации. Возвращает число принятых записей. */
    suspend fun importJson(text: String): Int = importSnapshot(Backup.parse(text))

    /**
     * Слияние слепка с местной базой. Правила:
     * - надгробия применяются первыми: что удалили на другом устройстве, удаляется и здесь и больше не принимается;
     * - списки, слова, домашки и прогресс по правилам берутся более поздние по updatedAt; при равном времени и разном
     *   содержимом побеждает чужая версия (иначе две копии со старыми записями без updatedAt расходились бы навсегда
     *   и пересылали файл друг другу при каждом проходе), при одинаковом содержимом ничего не пишется;
     * - попытки, контроши, проверки, рассказы, тексты, правила и журнал занятий после создания не меняются, поэтому
     *   добавляются только новые по id;
     * - дочерние записи без родителя (слово без списка, проверка без домашки) пропускаются, иначе упадёт внешний ключ;
     * - родители обновляются через UPDATE, не через REPLACE: REPLACE удалил бы строку и каскадом её детей.
     * Возвращает число добавленных, обновлённых и удалённых записей.
     */
    suspend fun importSnapshot(s: Snapshot): Int = db.withTransaction {
        var changed = 0
        val known = tombstones.all().map { it.id }.toHashSet()
        val incomingGraves = s.tombstones.filter { it.id !in known }
        incomingGraves.forEach { if (applyTombstone(it)) changed++ }
        tombstones.insertAll(incomingGraves)
        val dead = known + s.tombstones.map { it.id }

        val listIds = lists.allLists().map { it.id }.toHashSet()
        for (l in s.lists) {
            if (l.id in dead) continue
            val existing = lists.getList(l.id)
            when {
                existing == null -> { lists.insertList(l); listIds += l.id; changed++ }
                l.newerThan(existing.updatedAt, existing) -> { lists.updateList(l); changed++ }
            }
        }
        val newItems = s.items.filter { it.id !in dead && it.listId in listIds }.filter { i ->
            val existing = lists.getItem(i.id)
            existing == null || i.newerThan(existing.updatedAt, existing)
        }
        lists.insertItems(newItems)
        changed += newItems.size

        changed += insertMissing(attempts.allIds(), s.attempts.filter { it.id !in dead }, { it.id }) { attempts.insertAll(it) }
        val storyIds = stories.allIds().toHashSet()
        changed += insertMissing(storyIds, s.stories.filter { it.id !in dead && it.listId in listIds }, { it.id }) { batch ->
            batch.forEach { stories.insert(it) }
            storyIds += batch.map { it.id }
        }
        changed += insertMissing(quizRuns.allIds(), s.quizRuns.filter { it.id !in dead && it.listId !in dead }, { it.id }) { quizRuns.insertAll(it) }

        val homeworkIds = homeworks.all().map { it.id }.toHashSet()
        for (h in s.homeworks) {
            if (h.id in dead) continue
            val existing = homeworks.get(h.id)
            when {
                existing == null -> { homeworks.insert(h); homeworkIds += h.id; changed++ }
                h.newerThan(existing.updatedAt, existing) -> { homeworks.update(h); changed++ }
            }
        }
        changed += insertMissing(homeworks.allCheckIds(), s.homeworkChecks.filter { it.id !in dead && it.homeworkId in homeworkIds }, { it.id }) { homeworks.insertChecks(it) }

        val setIds = grammar.allSetIds().toHashSet()
        changed += insertMissing(setIds, s.grammarSets.filter { it.id !in dead }, { it.id }) { batch ->
            batch.forEach { grammar.insertSet(it) }
            setIds += batch.map { it.id }
        }
        for (p in s.grammarProgress) {
            if (p.setId !in setIds) continue
            val existing = grammar.getProgress(p.setId, p.ruleIndex)
            if (existing == null || p.newerThan(existing.updatedAt, existing)) { grammar.upsertProgress(p); changed++ }
        }

        val textIds = reading.allTextIds().toHashSet()
        changed += insertMissing(textIds, s.readingTexts.filter { it.id !in dead }, { it.id }) { batch ->
            batch.forEach { reading.insertText(it) }
            textIds += batch.map { it.id }
        }
        // Чтения ссылаются либо на текст, либо на рассказ; без родителя им негде показываться.
        changed += insertMissing(reading.allRunIds(), s.readingRuns.filter { it.id !in dead && (it.textId in textIds || it.textId in storyIds) }, { it.id }) { reading.insertRuns(it) }
        changed += insertMissing(activity.allIds(), s.activity.filter { it.id !in dead }, { it.id }) { activity.insertAll(it) }
        changed
    }

    /** Чужая запись побеждает, если она новее или ровесница с другим содержимым; одинаковую не трогаем. */
    private fun WordList.newerThan(existingUpdatedAt: Long, existing: WordList) = updatedAt > existingUpdatedAt || (updatedAt == existingUpdatedAt && this != existing)
    private fun WordItem.newerThan(existingUpdatedAt: Long, existing: WordItem) = updatedAt > existingUpdatedAt || (updatedAt == existingUpdatedAt && this != existing)
    private fun Homework.newerThan(existingUpdatedAt: Long, existing: Homework) = updatedAt > existingUpdatedAt || (updatedAt == existingUpdatedAt && this != existing)
    private fun GrammarProgress.newerThan(existingUpdatedAt: Long, existing: GrammarProgress) = updatedAt > existingUpdatedAt || (updatedAt == existingUpdatedAt && this != existing)

    private suspend fun <T> insertMissing(existingIds: Collection<String>, incoming: List<T>, id: (T) -> String, insert: suspend (List<T>) -> Unit): Int {
        val have = if (existingIds is Set<String>) existingIds else existingIds.toHashSet()
        val fresh = incoming.filter { id(it) !in have }.distinctBy(id)
        if (fresh.isNotEmpty()) insert(fresh)
        return fresh.size
    }

    /** Удаление по надгробию с другого устройства; true, если что-то удалилось. */
    private suspend fun applyTombstone(t: Tombstone): Boolean = when (t.kind) {
        Tombstone.LIST -> lists.getList(t.id)?.let { lists.deleteList(t.id); true } ?: false
        Tombstone.ITEM -> lists.getItem(t.id)?.let { lists.deleteItemById(t.id); true } ?: false
        Tombstone.ATTEMPT -> { attempts.delete(t.id); true }
        Tombstone.STORY -> { reading.deleteRunsForText(t.id); stories.deleteById(t.id); true }
        Tombstone.HOMEWORK -> homeworks.get(t.id)?.let { homeworks.delete(t.id); true } ?: false
        Tombstone.GRAMMAR_SET -> { grammar.deleteSet(t.id); true }
        Tombstone.READING_TEXT -> { reading.deleteRunsForText(t.id); reading.deleteText(t.id); true }
        Tombstone.READING_RUN -> { reading.deleteRun(t.id); true }
        else -> false
    }

    /** Помечает удалённую запись, чтобы синхронизация не вернула её обратно. Вызывать внутри транзакции удаления. */
    private suspend fun bury(kind: String, id: String) = tombstones.insert(Tombstone(id = id, kind = kind, ts = System.currentTimeMillis()))

    suspend fun resetAll() {
        db.withTransaction {
            attempts.clear()
            lists.clearLists()
            attempts.insertAll(Seed.attempts())
        }
    }

    // ---- Журнал занятий для общей статистики ----
    fun observeActivity(): Flow<List<ActivityLog>> = activity.observeAll()
    suspend fun logActivity(kind: String, refId: String?, durationMs: Long, total: Int, correct: Int) {
        if (total <= 0) return
        activity.insert(ActivityLog(id = newId(), ts = System.currentTimeMillis(), kind = kind, refId = refId, durationMs = durationMs.coerceAtLeast(0), total = total, correct = correct.coerceIn(0, total)))
    }

    // ---- Разбор всей домашки ----
    fun observeIntakeJobs(): Flow<List<IntakeJob>> = intake.observeAll()
    suspend fun getIntakeJob(id: String): IntakeJob? = intake.get(id)
    suspend fun queuedIntakeJobs(): List<IntakeJob> = intake.queued()
    suspend fun runningIntakeJobs(): List<IntakeJob> = intake.running()
    suspend fun saveIntakeJob(job: IntakeJob) = intake.upsert(job.copy(updatedAt = System.currentTimeMillis()))
    suspend fun deleteIntakeJob(id: String) = intake.delete(id)
    fun newIntakeId(): String = newId()

    private fun newId() = UUID.randomUUID().toString()
}
