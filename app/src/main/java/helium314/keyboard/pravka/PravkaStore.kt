// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.pravka

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

// The shared Pravka data layer, ported from the Pravka app: the fix history
// (JSONL journal the owner exports for quality analysis - his explicit
// choice to persist full texts) and the personal dictionary that feeds both
// the prompt and the recognizer biasing. One file store serves the keyboard,
// the panel hub and the floating button - they are one app now.
object PravkaStore {

    // One low-priority thread keeps append order intact and off the UI path.
    private val disk = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pravka-disk").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    // ---- history ----

    private fun historyFile(context: Context) = File(context.filesDir, "pravka-history.jsonl")

    fun appendHistory(
        context: Context,
        source: String,           // "keyboard" | "fab"
        result: PravkaApi.FixResult?,
        input: String,
        output: String,
        changed: Boolean,
        error: String?,
    ) {
        val at = Date()
        val app = context.applicationContext
        disk.execute {
            runCatching {
                val f = historyFile(app)
                if (f.exists() && f.length() > 5L * 1024 * 1024) {
                    val backup = File(app.filesDir, "pravka-history.jsonl.1")
                    backup.delete()
                    f.renameTo(backup)
                }
                val entry = JSONObject().apply {
                    put("ts", stamp.format(at))
                    put("source", source)
                    put("model", result?.model ?: "")
                    put("latency_ms", result?.latencyMs ?: 0)
                    put("input_tokens", result?.inputTokens ?: 0)
                    if ((result?.cacheWriteTokens ?: 0) > 0) put("cache_write_tokens", result!!.cacheWriteTokens)
                    if ((result?.cacheReadTokens ?: 0) > 0) put("cache_read_tokens", result!!.cacheReadTokens)
                    put("output_tokens", result?.outputTokens ?: 0)
                    put("cost_usd", result?.costUsd ?: 0.0)
                    put("changed", changed)
                    put("input", input)
                    put("output", output)
                    if (error != null) put("error", error)
                }
                f.appendText(entry.toString() + "\n")
            }
        }
    }

    /** Newest-first entries for the history screen (bounded). */
    fun readHistory(context: Context, limit: Int): List<JSONObject> =
        runCatching {
            historyFile(context).takeIf { it.exists() }?.readLines()?.asReversed()
                ?.asSequence()
                ?.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                ?.take(limit)?.toList()
        }.getOrNull() ?: emptyList()

    /** Recent successful CHANGED fixes as input->output pairs (miner food). */
    fun readPairs(context: Context, limit: Int): List<Pair<String, String>> =
        readHistory(context, 2000).asSequence()
            .filter { !it.has("error") && it.optBoolean("changed") }
            .map { it.optString("input") to it.optString("output") }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .take(limit)
            .toList()

    // ---- diagnostic event log: the recognizer's internals (segment mode,
    // biasing size, restarts, errors) - the evidence that pinpoints every
    // "почему не расшифровалось". Viewable from the history screen. ----

    private fun eventsFile(context: Context) = File(context.filesDir, "pravka-events.log")

    fun logEvent(context: Context, line: String) {
        val at = Date()
        val app = context.applicationContext
        disk.execute {
            runCatching {
                val f = eventsFile(app)
                if (f.exists() && f.length() > 512L * 1024) {
                    val backup = File(app.filesDir, "pravka-events.log.1")
                    backup.delete()
                    f.renameTo(backup)
                }
                f.appendText(stamp.format(at) + " " + line + "\n")
            }
        }
    }

    /** The newest [limit] journal lines, oldest first. */
    fun readEvents(context: Context, limit: Int): List<String> =
        runCatching {
            eventsFile(context).takeIf { it.exists() }?.readLines()?.takeLast(limit)
        }.getOrNull() ?: emptyList()

    // ---- dictionary ----

    enum class DictMode { HARD, HINT, PROTECT }

    data class DictEntry(
        val id: Long,
        val from: String,
        val to: String,
        val mode: DictMode,
        val note: String = "",
        val enabled: Boolean = true,
        val hits: Int = 0,
    )

    private fun dictFile(context: Context) = File(context.filesDir, "pravka-dictionary.json")

    @Volatile private var dictCache: List<DictEntry>? = null

    fun dictionary(context: Context): List<DictEntry> {
        dictCache?.let { return it }
        val loaded = runCatching {
            val f = dictFile(context)
            if (!f.exists()) return@runCatching emptyList()
            val array = JSONArray(f.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                DictEntry(
                    id = o.optLong("id"),
                    from = o.optString("from"),
                    to = o.optString("to"),
                    mode = runCatching { DictMode.valueOf(o.optString("mode")) }.getOrNull() ?: return@mapNotNull null,
                    note = o.optString("note"),
                    enabled = o.optBoolean("enabled", true),
                    hits = o.optInt("hits"),
                )
            }
        }.getOrDefault(emptyList())
        dictCache = loaded
        return loaded
    }

    fun saveDictionary(context: Context, entries: List<DictEntry>) {
        dictCache = entries
        val app = context.applicationContext
        disk.execute {
            runCatching {
                val array = JSONArray()
                entries.forEach { e ->
                    array.put(
                        JSONObject().apply {
                            put("id", e.id)
                            put("from", e.from)
                            put("to", e.to)
                            put("mode", e.mode.name)
                            put("note", e.note)
                            put("enabled", e.enabled)
                            put("hits", e.hits)
                        }
                    )
                }
                dictFile(app).writeText(array.toString())
            }
        }
    }

    fun addEntry(context: Context, from: String, to: String, mode: DictMode, note: String = "") {
        val entries = dictionary(context)
        val id = (entries.maxOfOrNull { it.id } ?: 0L) + 1
        saveDictionary(context, entries + DictEntry(id, from.trim(), to.trim(), mode, note))
    }

    /** Words the recognizer should be biased toward (names, brands, terms). */
    fun biasingWords(context: Context): List<String> {
        val words = LinkedHashSet<String>()
        dictionary(context).filter { it.enabled }.forEach { e ->
            e.from.takeIf { it.isNotBlank() }?.let { words.add(it) }
            e.to.takeIf { it.isNotBlank() }?.let { words.add(it) }
        }
        return words.toList()
    }

    // ---- export / share ----

    /** The Pravka app's export format (root object with "entries"). */
    fun exportDictionaryJson(context: Context): String {
        val array = JSONArray()
        dictionary(context).forEach { e ->
            array.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("from", e.from)
                    put("to", e.to)
                    put("mode", e.mode.name)
                    put("note", e.note)
                    put("enabled", e.enabled)
                    put("hits", e.hits)
                }
            )
        }
        return JSONObject().put("entries", array).toString(2)
    }

    fun historyRawText(context: Context): String =
        runCatching { historyFile(context).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    fun eventsRawText(context: Context): String =
        runCatching { eventsFile(context).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    /** Writes [content] into the export cache and opens the system share sheet. */
    fun shareTextFile(context: Context, fileName: String, mime: String, content: String): Boolean =
        runCatching {
            val dir = File(context.cacheDir, "pravka_export").apply { mkdirs() }
            val f = File(dir, fileName)
            f.writeText(content)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".pravka.files", f,
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, fileName)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess

    // ---- migration from the standalone Pravka app ----

    /**
     * Imports a dictionary JSON exported by the Pravka app ("Экспорт JSON":
     * a root object with an "entries" array) or this app's own bare-array
     * format. Merges by (from, mode), existing entries win. Returns the
     * number of NEW entries added.
     */
    fun importDictionaryJson(context: Context, text: String): Result<Int> = runCatching {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("{")) JSONObject(trimmed).getJSONArray("entries")
            else JSONArray(trimmed)
        val existing = dictionary(context)
        val known = existing.map { it.from.lowercase() to it.mode }.toHashSet()
        var nextId = (existing.maxOfOrNull { it.id } ?: 0L) + 1
        val added = mutableListOf<DictEntry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val from = o.optString("from").trim()
            if (from.isEmpty()) continue
            val mode = runCatching { DictMode.valueOf(o.optString("mode", "HARD")) }.getOrNull() ?: continue
            if ((from.lowercase() to mode) in known) continue
            added.add(
                DictEntry(
                    id = nextId++,
                    from = from,
                    to = o.optString("to").trim(),
                    mode = mode,
                    note = o.optString("note").trim(),
                    enabled = o.optBoolean("enabled", true),
                    hits = o.optInt("hits"),
                )
            )
        }
        if (added.isNotEmpty()) saveDictionary(context, existing + added)
        added.size
    }

    /**
     * Imports the Pravka app's history JSONL ("Выгрузить историю"). The
     * imported lines are older than anything local, so they go FIRST and the
     * local journal is appended after them. Returns the number of lines taken.
     */
    fun importHistoryJsonl(context: Context, text: String): Result<Int> = runCatching {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { line -> runCatching { JSONObject(line) }.isSuccess }
            .toList()
        require(lines.isNotEmpty()) { "В файле нет записей истории." }
        val f = historyFile(context)
        val current = if (f.exists()) f.readText() else ""
        f.writeText(lines.joinToString("\n", postfix = "\n") + current)
        lines.size
    }

    // ---- dictionary application (ported from the Pravka app's applier) ----

    data class Prepared(val text: String, val dictBlock: String)

    /**
     * HARD entries are replaced in the text before the request; HINT and
     * PROTECT entries that OCCUR in the input become the {DICT} block.
     */
    fun prepare(context: Context, input: String): Prepared {
        var text = input
        val relevant = mutableListOf<DictEntry>()
        dictionary(context).filter { it.enabled }.forEach { e ->
            when (e.mode) {
                DictMode.HARD -> {
                    val regex = runCatching { Regex("(?i)\\b" + Regex.escape(e.from) + "\\b") }.getOrNull()
                    if (regex != null && regex.containsMatchIn(text)) {
                        text = regex.replace(text, Regex.escapeReplacement(e.to))
                    }
                }
                DictMode.HINT, DictMode.PROTECT -> {
                    if (text.contains(e.from, ignoreCase = true)) relevant.add(e)
                }
            }
        }
        if (relevant.isEmpty()) return Prepared(text, "")
        val block = buildString {
            append("Словарь автора (соблюдай):\n")
            relevant.forEach { e ->
                when (e.mode) {
                    DictMode.PROTECT -> append("- \"${e.from}\" — правильное написание, не изменяй его")
                    else -> append("- \"${e.from}\" пиши как \"${e.to}\"")
                }
                if (e.note.isNotBlank()) append(" (${e.note})")
                append('\n')
            }
        }.trim()
        return Prepared(text, block)
    }
}
