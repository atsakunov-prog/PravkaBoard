package ru.tsakunov.pravka.domain

import org.json.JSONArray
import org.json.JSONObject
import ru.tsakunov.pravka.data.ActivityLog
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.GrammarProgress
import ru.tsakunov.pravka.data.GrammarSet
import ru.tsakunov.pravka.data.Homework
import ru.tsakunov.pravka.data.HomeworkCheck
import ru.tsakunov.pravka.data.QuizRun
import ru.tsakunov.pravka.data.ReadingRun
import ru.tsakunov.pravka.data.ReadingText
import ru.tsakunov.pravka.data.Story
import ru.tsakunov.pravka.data.Tombstone
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.data.WordList

/**
 * Полный слепок данных приложения: то, что уходит в резервную копию и в синхронизацию через GitHub.
 * Разборы всей домашки (intake_jobs) сюда не входят: они привязаны к телефону, который их запустил.
 */
data class Snapshot(
    val lists: List<WordList> = emptyList(),
    val items: List<WordItem> = emptyList(),
    val attempts: List<Attempt> = emptyList(),
    val stories: List<Story> = emptyList(),
    val quizRuns: List<QuizRun> = emptyList(),
    val homeworks: List<Homework> = emptyList(),
    val homeworkChecks: List<HomeworkCheck> = emptyList(),
    val grammarSets: List<GrammarSet> = emptyList(),
    val grammarProgress: List<GrammarProgress> = emptyList(),
    val readingTexts: List<ReadingText> = emptyList(),
    val readingRuns: List<ReadingRun> = emptyList(),
    val activity: List<ActivityLog> = emptyList(),
    val tombstones: List<Tombstone> = emptyList(),
    val exportedAt: Long = 0,
    /** Устройство, с которого сделан слепок: видно в истории коммитов ветки data. */
    val device: String = "",
) {
    val isEmpty: Boolean
        get() = lists.isEmpty() && items.isEmpty() && attempts.isEmpty() && stories.isEmpty() && quizRuns.isEmpty() &&
            homeworks.isEmpty() && homeworkChecks.isEmpty() && grammarSets.isEmpty() && grammarProgress.isEmpty() &&
            readingTexts.isEmpty() && readingRuns.isEmpty() && activity.isEmpty() && tombstones.isEmpty()
}

/**
 * JSON резервной копии. Версия 2 добавляет к спискам, словам и попыткам всё остальное плюс надгробия.
 * Файлы версии 1 читаются как раньше. Разбор терпимый: незнакомые ключи пропускаются, битые записи тоже.
 * Записи внутри каждого раздела выводятся в порядке идентификаторов, чтобы одинаковые данные с разных
 * устройств давали одинаковый текст (см. [fingerprint]).
 */
object Backup {
    const val VERSION = 2

    fun toJson(s: Snapshot, pretty: Boolean = false): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("exportedAt", s.exportedAt)
        root.put("device", s.device)
        root.put("lists", arr(s.lists.sortedBy { it.id }) { l ->
            JSONObject().put("id", l.id).put("title", l.title).put("createdAt", l.createdAt).put("updatedAt", l.updatedAt)
        })
        root.put("items", arr(s.items.sortedBy { it.id }) { i ->
            JSONObject().put("id", i.id).put("listId", i.listId).put("en", i.en).put("ru", i.ru)
                .put("kind", i.kind).put("position", i.position).put("updatedAt", i.updatedAt)
        })
        root.put("attempts", arr(s.attempts.sortedBy { it.id }) { a ->
            JSONObject().put("id", a.id).put("ts", a.ts).put("lang", a.lang)
                .put("word", a.word ?: JSONObject.NULL).put("letters", a.letters).put("ms", a.ms)
                .put("source", a.source).put("listId", a.listId ?: JSONObject.NULL)
                .put("itemId", a.itemId ?: JSONObject.NULL)
        })
        root.put("stories", arr(s.stories.sortedBy { it.id }) { st ->
            JSONObject().put("id", st.id).put("listId", st.listId).put("title", st.title)
                .put("textEn", st.textEn).put("textRu", st.textRu).put("createdAt", st.createdAt)
        })
        root.put("quizRuns", arr(s.quizRuns.sortedBy { it.id }) { q ->
            JSONObject().put("id", q.id).put("listId", q.listId).put("ts", q.ts)
                .put("attempts", q.attempts).put("durationMs", q.durationMs).put("words", q.words)
        })
        root.put("homeworks", arr(s.homeworks.sortedBy { it.id }) { h ->
            JSONObject().put("id", h.id).put("title", h.title).put("createdAt", h.createdAt).put("updatedAt", h.updatedAt)
        })
        root.put("homeworkChecks", arr(s.homeworkChecks.sortedBy { it.id }) { c ->
            JSONObject().put("id", c.id).put("homeworkId", c.homeworkId).put("ts", c.ts).put("attemptNo", c.attemptNo)
                .put("correct", c.correct).put("total", c.total).put("resultJson", c.resultJson)
        })
        root.put("grammarSets", arr(s.grammarSets.sortedBy { it.id }) { g ->
            JSONObject().put("id", g.id).put("title", g.title).put("createdAt", g.createdAt).put("contentJson", g.contentJson)
        })
        root.put("grammarProgress", arr(s.grammarProgress.sortedWith(compareBy({ it.setId }, { it.ruleIndex }))) { p ->
            JSONObject().put("setId", p.setId).put("ruleIndex", p.ruleIndex).put("passed", p.passed)
                .put("bestStreak", p.bestStreak).put("correct", p.correct).put("total", p.total).put("updatedAt", p.updatedAt)
        })
        root.put("readingTexts", arr(s.readingTexts.sortedBy { it.id }) { t ->
            JSONObject().put("id", t.id).put("title", t.title).put("textEn", t.textEn).put("textRu", t.textRu)
                .put("words", t.words).put("createdAt", t.createdAt)
        })
        root.put("readingRuns", arr(s.readingRuns.sortedBy { it.id }) { r ->
            JSONObject().put("id", r.id).put("textId", r.textId).put("ts", r.ts).put("durationMs", r.durationMs)
                .put("stumbles", r.stumbles).put("words", r.words).put("mode", r.mode)
                .put("readingMs", r.readingMs ?: JSONObject.NULL).put("sentences", r.sentences ?: JSONObject.NULL)
                .put("readOk", r.readOk ?: JSONObject.NULL).put("transOk", r.transOk ?: JSONObject.NULL)
                .put("detailJson", r.detailJson ?: JSONObject.NULL)
        })
        root.put("activity", arr(s.activity.sortedBy { it.id }) { a ->
            JSONObject().put("id", a.id).put("ts", a.ts).put("kind", a.kind).put("refId", a.refId ?: JSONObject.NULL)
                .put("durationMs", a.durationMs).put("total", a.total).put("correct", a.correct)
        })
        root.put("tombstones", arr(s.tombstones.sortedBy { it.id }) { t ->
            JSONObject().put("id", t.id).put("kind", t.kind).put("ts", t.ts)
        })
        return if (pretty) root.toString(2) else root.toString()
    }

    fun parse(text: String): Snapshot {
        val root = JSONObject(text)
        val now = System.currentTimeMillis()
        return Snapshot(
            exportedAt = root.optLong("exportedAt", 0L),
            device = root.optString("device", ""),
            lists = objects(root, "lists").mapNotNull { o ->
                val id = o.str("id") ?: return@mapNotNull null
                WordList(id, o.optString("title", "Список"), o.optLong("createdAt", now), o.optLong("updatedAt", 0L))
            },
            items = objects(root, "items").mapIndexedNotNull { k, o ->
                val id = o.str("id") ?: return@mapIndexedNotNull null
                val listId = o.str("listId") ?: return@mapIndexedNotNull null
                WordItem(id, listId, o.optString("en", ""), o.optString("ru", ""), o.optString("kind", "word"), o.optInt("position", k), o.optLong("updatedAt", 0L))
            },
            attempts = objects(root, "attempts").mapNotNull { o ->
                val letters = o.optInt("letters", 0)
                val ms = o.optLong("ms", 0)
                if (letters <= 0 || ms <= 0) return@mapNotNull null
                Attempt(
                    id = o.str("id") ?: return@mapNotNull null, ts = o.optLong("ts", now),
                    lang = o.optString("lang", "en"), word = o.str("word"),
                    letters = letters, ms = ms, source = o.optString("source", Attempt.SOURCE_MANUAL),
                    listId = o.str("listId"), itemId = o.str("itemId"),
                )
            },
            stories = objects(root, "stories").mapNotNull { o ->
                Story(
                    id = o.str("id") ?: return@mapNotNull null, listId = o.str("listId") ?: return@mapNotNull null,
                    title = o.optString("title", ""), textEn = o.optString("textEn", ""), textRu = o.optString("textRu", ""),
                    createdAt = o.optLong("createdAt", now),
                )
            },
            quizRuns = objects(root, "quizRuns").mapNotNull { o ->
                QuizRun(
                    id = o.str("id") ?: return@mapNotNull null, listId = o.str("listId") ?: return@mapNotNull null,
                    ts = o.optLong("ts", now), attempts = o.optInt("attempts", 1), durationMs = o.optLong("durationMs", 0L), words = o.optInt("words", 0),
                )
            },
            homeworks = objects(root, "homeworks").mapNotNull { o ->
                val id = o.str("id") ?: return@mapNotNull null
                val created = o.optLong("createdAt", now)
                Homework(id, o.optString("title", "Домашка"), created, o.optLong("updatedAt", created))
            },
            homeworkChecks = objects(root, "homeworkChecks").mapNotNull { o ->
                HomeworkCheck(
                    id = o.str("id") ?: return@mapNotNull null, homeworkId = o.str("homeworkId") ?: return@mapNotNull null,
                    ts = o.optLong("ts", now), attemptNo = o.optInt("attemptNo", 1), correct = o.optInt("correct", 0),
                    total = o.optInt("total", 0), resultJson = o.optString("resultJson", "{}"),
                )
            },
            grammarSets = objects(root, "grammarSets").mapNotNull { o ->
                GrammarSet(
                    id = o.str("id") ?: return@mapNotNull null, title = o.optString("title", "Правило"),
                    createdAt = o.optLong("createdAt", now), contentJson = o.optString("contentJson", "{}"),
                )
            },
            grammarProgress = objects(root, "grammarProgress").mapNotNull { o ->
                GrammarProgress(
                    setId = o.str("setId") ?: return@mapNotNull null, ruleIndex = o.optInt("ruleIndex", -1).takeIf { it >= 0 } ?: return@mapNotNull null,
                    passed = o.optBoolean("passed", false), bestStreak = o.optInt("bestStreak", 0),
                    correct = o.optInt("correct", 0), total = o.optInt("total", 0), updatedAt = o.optLong("updatedAt", 0L),
                )
            },
            readingTexts = objects(root, "readingTexts").mapNotNull { o ->
                ReadingText(
                    id = o.str("id") ?: return@mapNotNull null, title = o.optString("title", "Текст"),
                    textEn = o.optString("textEn", ""), textRu = o.optString("textRu", ""),
                    words = o.optInt("words", 0), createdAt = o.optLong("createdAt", now),
                )
            },
            readingRuns = objects(root, "readingRuns").mapNotNull { o ->
                ReadingRun(
                    id = o.str("id") ?: return@mapNotNull null, textId = o.str("textId") ?: return@mapNotNull null,
                    ts = o.optLong("ts", now), durationMs = o.optLong("durationMs", 0L), stumbles = o.optInt("stumbles", 0),
                    words = o.optInt("words", 0), mode = o.optString("mode", ReadingRun.MODE_TIMER),
                    readingMs = o.long("readingMs"), sentences = o.int("sentences"), readOk = o.int("readOk"),
                    transOk = o.int("transOk"), detailJson = o.str("detailJson"),
                )
            },
            activity = objects(root, "activity").mapNotNull { o ->
                ActivityLog(
                    id = o.str("id") ?: return@mapNotNull null, ts = o.optLong("ts", now), kind = o.optString("kind", ""),
                    refId = o.str("refId"), durationMs = o.optLong("durationMs", 0L), total = o.optInt("total", 0), correct = o.optInt("correct", 0),
                )
            },
            tombstones = objects(root, "tombstones").mapNotNull { o ->
                Tombstone(id = o.str("id") ?: return@mapNotNull null, kind = o.optString("kind", ""), ts = o.optLong("ts", now))
            },
        )
    }

    /** Отпечаток содержимого без времени выгрузки и имени устройства: совпал — отправлять нечего. */
    fun fingerprint(s: Snapshot): String = toJson(s.copy(exportedAt = 0L, device = ""))

    private fun <T> arr(list: List<T>, map: (T) -> JSONObject): JSONArray = JSONArray().also { a -> list.forEach { a.put(map(it)) } }

    private fun objects(root: JSONObject, key: String): List<JSONObject> {
        val a = root.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /** Строка или null, если ключа нет, стоит JSON null или пусто (старые копии писали слово "null"). */
    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key)
        return v.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONObject.long(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
    private fun JSONObject.int(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
}
