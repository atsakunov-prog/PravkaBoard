package ru.tsakunov.pravka.domain

import org.json.JSONArray
import org.json.JSONObject

/** Одна карточка тренажёра: показываем prompt, ждём answer. */
data class Drill(val prompt: String, val answer: String, val note: String)

/** Правило = уровень тренажёра. */
data class GrammarRule(
    val name: String,
    val explanation: String,
    val examples: List<Drill>,
    val drills: List<Drill>,
)

data class GrammarSetContent(val title: String, val rules: List<GrammarRule>) {

    fun toJson(): String {
        fun drills(list: List<Drill>) = JSONArray().also { arr ->
            list.forEach { arr.put(JSONObject().put("prompt", it.prompt).put("answer", it.answer).put("note", it.note)) }
        }
        val root = JSONObject().put("title", title)
        val rules = JSONArray()
        for (r in this.rules) {
            rules.put(JSONObject().put("name", r.name).put("explanation", r.explanation).put("examples", drills(r.examples)).put("drills", drills(r.drills)))
        }
        return root.put("rules", rules).toString()
    }

    companion object {
        const val STREAK_TO_PASS = 5

        fun fromJson(text: String): GrammarSetContent = fromJsonObject(JSONObject(text))

        fun fromJsonObject(root: JSONObject): GrammarSetContent {
            fun drills(arr: JSONArray?): List<Drill> {
                val out = ArrayList<Drill>()
                if (arr == null) return out
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optString("prompt").trim()
                    val a = o.optString("answer").trim()
                    if (p.isNotEmpty() && a.isNotEmpty()) out += Drill(p, a, o.optString("note").trim())
                }
                return out
            }
            val rules = ArrayList<GrammarRule>()
            val arr = root.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val d = drills(r.optJSONArray("drills"))
                if (d.isEmpty()) continue
                rules += GrammarRule(
                    name = r.optString("name").ifBlank { "Правило ${i + 1}" },
                    explanation = r.optString("explanation"),
                    examples = drills(r.optJSONArray("examples")),
                    drills = d,
                )
            }
            return GrammarSetContent(root.optString("title").ifBlank { "Грамматика" }, rules)
        }
    }
}
