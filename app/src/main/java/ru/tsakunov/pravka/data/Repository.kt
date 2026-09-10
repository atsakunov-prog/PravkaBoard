package ru.tsakunov.pravka.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Repository(
    private val db: AppDatabase,
    private val settings: Settings,
) {
    private val lists get() = db.wordListDao()
    private val attempts get() = db.attemptDao()

    // ---- Наблюдение ----
    fun observeLists(): Flow<List<WordListWithCount>> = lists.observeLists()
    fun observeList(id: String): Flow<WordList?> = lists.observeList(id)
    fun observeItems(listId: String): Flow<List<WordItem>> = lists.observeItems(listId)
    fun observeAttempts(): Flow<List<Attempt>> = attempts.observeAll()

    // ---- Посев ----
    suspend fun seedIfNeeded() {
        if (settings.seeded) return
        if (attempts.count() == 0) attempts.insertAll(Seed.attempts())
        settings.seeded = true
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
