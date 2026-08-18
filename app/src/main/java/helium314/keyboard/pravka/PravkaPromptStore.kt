// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.pravka

import android.content.Context

// User-editable prompt overrides (the app's PromptStore, on SharedPreferences):
// factory texts live in PravkaPrompts, an override replaces one at runtime.
object PravkaPromptStore {

    enum class PromptId(val title: String) {
        CLEAN("Мастер-промпт (чистка)"),
        REDO_POLISH("Причесать стиль"),
        REDO_SHORTER("Короче"),
        REDO_LONGER("Длиннее"),
        ASSIST_SUMMARY("Расскажи коротко"),
        ASSIST_REPLY("Что ответить"),
        ASSIST_TRANSLATE("Перевод"),
    }

    private const val PREFS = "pravka"
    private fun key(id: PromptId) = "prompt_override_" + id.name.lowercase()

    // Read on every request from binder threads - cache and refresh on writes.
    @Volatile private var cache: Map<PromptId, String>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun factory(id: PromptId): String = when (id) {
        PromptId.CLEAN -> PravkaPrompts.CLEAN
        PromptId.REDO_POLISH -> PravkaPrompts.REDO_POLISH
        PromptId.REDO_SHORTER -> PravkaPrompts.REDO_SHORTER
        PromptId.REDO_LONGER -> PravkaPrompts.REDO_LONGER
        PromptId.ASSIST_SUMMARY -> PravkaPrompts.ASSIST_SUMMARY
        PromptId.ASSIST_REPLY -> PravkaPrompts.ASSIST_REPLY
        PromptId.ASSIST_TRANSLATE -> PravkaPrompts.ASSIST_TRANSLATE
    }

    private fun loadCache(context: Context): Map<PromptId, String> =
        cache ?: PromptId.entries.mapNotNull { id ->
            prefs(context).getString(key(id), null)?.let { id to it }
        }.toMap().also { cache = it }

    fun override(context: Context, id: PromptId): String? = loadCache(context)[id]

    fun effective(context: Context, id: PromptId): String =
        override(context, id) ?: factory(id)

    fun setOverride(context: Context, id: PromptId, text: String) {
        prefs(context).edit().putString(key(id), text).apply()
        cache = null
    }

    fun resetToFactory(context: Context, id: PromptId) {
        prefs(context).edit().remove(key(id)).apply()
        cache = null
    }
}
