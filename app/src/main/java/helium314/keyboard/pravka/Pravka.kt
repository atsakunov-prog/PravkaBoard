// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.pravka

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Toast
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Pravka inside the keyboard: proofread the field via Claude, live dictation
// on the key area, one-tap redo chips on a stronger model. The IME's
// InputConnection is the delivery channel - unlike the accessibility overlay
// app, a write through it cannot land in the wrong field or be rejected.
class Pravka(private val ime: LatinIME) {

    companion object {
        private const val PREFS = "pravka"
        private const val KEY_API = "pravka_api_key"

        private var instance: Pravka? = null

        @JvmStatic
        fun get(ime: LatinIME): Pravka =
            instance?.takeIf { it.ime === ime } ?: Pravka(ime).also { instance = it }

        /** Input view is being hidden/torn down - stop the take, drop the overlay. */
        @JvmStatic
        fun onHideWindow() {
            instance?.onHide()
        }
    }

    private val crashLogger = CoroutineExceptionHandler { _, e ->
        toast("Правка: ${e.javaClass.simpleName} ${e.message ?: ""}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashLogger)
    private val overlay = PravkaOverlay().apply {
        onStop = { stopDictation() }
        onStopWith = { directive -> pendingDirective = directive; stopDictation() }
    }
    private var session: GoogleSpeechSession? = null
    private var busy = false

    // Set by the overlay's one-tap finishers ("Причесать"/"Короче"): applied
    // on top of the CLEAN pass for the take that is being stopped.
    private var pendingDirective: String = ""

    private fun prefs() = ime.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun apiKey(): String = prefs().getString(KEY_API, "").orEmpty()

    private fun toast(msg: String) =
        Toast.makeText(ime.applicationContext, msg, Toast.LENGTH_LONG).show()

    /** Returns true when the code was one of ours and has been handled. */
    fun onToolbarKey(code: Int): Boolean {
        when (code) {
            KeyCode.PRAVKA_CLEAN -> cleanField(directive = "", strong = false)
            KeyCode.PRAVKA_SHORTER -> cleanField(PravkaPrompts.REDO_SHORTER, strong = true)
            KeyCode.PRAVKA_LONGER -> cleanField(PravkaPrompts.REDO_LONGER, strong = true)
            KeyCode.PRAVKA_POLISH -> cleanField(PravkaPrompts.REDO_POLISH, strong = true)
            KeyCode.PRAVKA_VOICE -> toggleDictation()
            KeyCode.PRAVKA_SET_KEY -> setApiKeyFromClipboard()
            else -> return false
        }
        return true
    }

    // ---- API key: copy the key, long-press the Pravka toolbar key ----

    private fun setApiKeyFromClipboard() {
        val cm = ime.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (text.startsWith("sk-ant-") && text.length > 20) {
            prefs().edit().putString(KEY_API, text).apply()
            toast("API-ключ сохранён (${text.take(14)}…)")
        } else if (apiKey().isNotBlank()) {
            toast("Ключ уже задан. Чтобы заменить — скопируй новый ключ (sk-ant-…) и нажми ещё раз.")
        } else {
            toast("Скопируй API-ключ (sk-ant-…) в буфер и нажми долгим нажатием ещё раз.")
        }
    }

    // ---- Whole-field fix (Pravka button and the redo chips) ----

    private fun cleanField(directive: String, strong: Boolean) {
        if (busy) { toast("Уже работаю…"); return }
        if (session != null) { stopDictation(); return }
        val ic = ime.currentInputConnection ?: return
        val extracted = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
        val text = extracted?.text?.toString().orEmpty()
        if (text.isBlank()) { toast("Поле пустое — нечего править."); return }
        val key = apiKey()
        if (key.isBlank()) { toast("Нет API-ключа: Настройки клавиатуры → Правка."); return }

        busy = true
        overlay.show(if (strong) "…" else text.takeLast(400))
        overlay.hideButtons()  // buttons belong to dictation, not the field fix
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PravkaApi.proofread(
                    apiKey = key,
                    input = text,
                    directive = directive,
                    model = if (strong) PravkaApi.MODEL_OPUS else PravkaApi.MODEL_SONNET,
                    onDelta = { partial -> scope.launch { overlay.update(partial.takeLast(1200)) } },
                )
            }
            busy = false
            overlay.hide()
            result.onSuccess { cleaned ->
                if (cleaned.trim() == text.trim()) {
                    toast("Без изменений")
                } else {
                    replaceWholeField(cleaned)
                    copyToClipboard(cleaned)
                }
            }.onFailure { e -> toast(e.message ?: "Ошибка Правки") }
        }
    }

    private fun replaceWholeField(newText: String) {
        val ic = ime.currentInputConnection ?: return
        runCatching {
            ic.beginBatchEdit()
            ic.finishComposingText()
            val len = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.length ?: Int.MAX_VALUE
            ic.setSelection(0, len)
            ic.commitText(newText, 1)
            ic.endBatchEdit()
        }
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val cm = ime.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Правка", text))
        }
    }

    // ---- Dictation: the key area becomes the live text panel ----

    private fun toggleDictation() {
        if (session != null) { stopDictation(); return }
        if (busy) { toast("Уже работаю…"); return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(ime, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(ime, MicPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ime.startActivity(intent)
            return
        }
        if (!GoogleSpeechSession.isAvailable(ime)) { toast("Распознавание речи недоступно"); return }

        val s = GoogleSpeechSession(ime)
        session = s
        pendingDirective = ""
        overlay.show("Говори…")
        s.start(
            onPartial = { live -> overlay.update(live.takeLast(1200)) },
            onCheckpoint = { },
            onDone = { text -> onDictationDone(text) },
            onError = { msg ->
                session = null
                overlay.hide()
                toast(msg)
            },
        )
    }

    private fun stopDictation() {
        session?.stop()
    }

    private fun onDictationDone(rawText: String) {
        session = null
        overlay.hideButtons()
        val text = VoiceCommands.apply(rawText)
        if (text.isBlank()) {
            overlay.hide()
            toast("Ничего не расслышал")
            return
        }
        val key = apiKey()
        if (key.isBlank()) {
            // No key - at least deliver the raw take.
            overlay.hide()
            insertAtCursor(text)
            toast("Вставил без чистки: нет API-ключа (Настройки → Правка)")
            return
        }
        busy = true
        overlay.update("…")
        // "Причесать"/"Короче" on the panel: an extra pass on the strong model.
        val directive = pendingDirective
        pendingDirective = ""
        scope.launch {
            val context = contextBeforeCursor()
            val result = withContext(Dispatchers.IO) {
                PravkaApi.proofread(
                    apiKey = key,
                    input = text,
                    directive = directive,
                    contextBefore = context,
                    model = if (directive.isBlank()) PravkaApi.MODEL_SONNET else PravkaApi.MODEL_OPUS,
                    onDelta = { partial -> scope.launch { overlay.update(partial.takeLast(1200)) } },
                )
            }
            busy = false
            overlay.hide()
            val final = result.getOrNull() ?: text  // never lose the words
            insertAtCursor(final)
            copyToClipboard(final)
            result.onFailure { e -> toast("Вставил без чистки: ${e.message}") }
        }
    }

    private fun contextBeforeCursor(): String {
        val ic = ime.currentInputConnection ?: return ""
        return runCatching { ic.getTextBeforeCursor(300, 0)?.toString().orEmpty() }
            .getOrDefault("")
    }

    private fun insertAtCursor(text: String) {
        val ic = ime.currentInputConnection ?: return
        runCatching {
            ic.beginBatchEdit()
            ic.finishComposingText()
            val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            val needsSpace = before.isNotEmpty() && !before.last().isWhitespace()
            ic.commitText((if (needsSpace) " " else "") + text, 1)
            ic.endBatchEdit()
        }
    }

    /** The input view is being torn down - drop UI and the live session. */
    fun onHide() {
        runCatching { session?.stop() }
        overlay.hide()
    }
}
