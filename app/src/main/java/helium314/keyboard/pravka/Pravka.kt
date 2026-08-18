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
            // Our own re-render (setThemeNeedsReload hides+shows the IME) must
            // not wipe the very state it is repainting.
            if (internalReload) return
            instance?.onHide()
            if (selectionLatch) {
                selectionLatch = false
                helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().markThemeNeedsReload()
            }
            instance?.disarmNumbersQuietly()
        }

        /** While true, arrow keys extend the selection (direct setSelection). */
        @JvmStatic
        var selectionLatch = false
            private set

        /** While true, the always-visible number row actually types digits. */
        @JvmStatic
        var numbersArmed = false
            private set

        private var internalReload = false
    }

    /** Rebuild the keyboard so key state (latch color, digit dimming) repaints. */
    private fun reloadKeyboard() {
        internalReload = true
        try {
            helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().setThemeNeedsReload()
        } finally {
            internalReload = false
        }
    }

    private val crashLogger = CoroutineExceptionHandler { _, e ->
        toast("Правка: ${e.javaClass.simpleName} ${e.message ?: ""}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashLogger)
    private val overlay = PravkaOverlay().apply {
        onTextTap = { if (session != null) stopDictation() }
    }
    private var session: GoogleSpeechSession? = null
    private var busy = false

    // Set by the overlay's one-tap finishers ("Причесать"/"Короче"): applied
    // on top of the CLEAN pass for the take that is being stopped.
    private var pendingDirective: String = ""

    /** Effective (possibly user-edited) prompt text for [id]. */
    private fun redo(id: PravkaPromptStore.PromptId) = PravkaPromptStore.effective(ime, id)

    private fun prefs() = ime.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun apiKey(): String = prefs().getString(KEY_API, "").orEmpty()

    private fun toast(msg: String) =
        Toast.makeText(ime.applicationContext, msg, Toast.LENGTH_LONG).show()

    /**
     * Returns true when the code was one of ours and has been handled.
     * [fromToolbar]: the press came from the toolbar (opens the panel hub)
     * rather than a keyboard key (acts immediately).
     */
    fun onToolbarKey(code: Int, fromToolbar: Boolean = true): Boolean {
        when (code) {
            KeyCode.PRAVKA_CLEAN ->
                if (fromToolbar) showFixHub() else cleanField(directive = "", strong = false)
            KeyCode.PRAVKA_SHORTER -> cleanField(redo(PravkaPromptStore.PromptId.REDO_SHORTER), strong = true)
            KeyCode.PRAVKA_LONGER -> cleanField(redo(PravkaPromptStore.PromptId.REDO_LONGER), strong = true)
            KeyCode.PRAVKA_POLISH -> cleanField(redo(PravkaPromptStore.PromptId.REDO_POLISH), strong = true)
            KeyCode.PRAVKA_VOICE ->
                if (fromToolbar && session == null && !busy) showDictationLobby()
                else toggleDictation()
            KeyCode.PRAVKA_SET_KEY -> setApiKeyFromClipboard()
            KeyCode.PRAVKA_SELECT -> {
                selectionLatch = !selectionLatch
                selAnchor = -1
                reloadKeyboard()  // the latch key repaints as pressed/released
            }
            KeyCode.PRAVKA_NUMROW -> toggleNumbersArmed()
            KeyCode.PRAVKA_SETTINGS -> openSettings(helium314.keyboard.settings.SettingsDestination.Pravka)
            else -> return false
        }
        return true
    }

    /** Opens the keyboard settings straight at a Pravka screen. */
    private fun openSettings(destination: String) {
        overlay.hide()
        ime.requestHideSelf(0)
        // The extra covers a cold start; navigateTo covers an already-open activity.
        helium314.keyboard.settings.SettingsDestination.navigateTo(destination)
        runCatching {
            ime.startActivity(
                Intent(ime, helium314.keyboard.settings.SettingsActivity2::class.java)
                    .putExtra("destination", destination)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
            )
        }
    }

    // The selection anchor: fixed where the cursor stood when the latch was
    // switched on; arrows move the OTHER end. Direct setSelection is used
    // instead of shift+arrow key events - editors handle it uniformly.
    private var selAnchor = -1

    /** Arrow key while the selection latch is on: extend the selection. */
    fun onSelectionArrow(code: Int) {
        val ic = ime.currentInputConnection ?: return
        runCatching {
            val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
            val text = et.text?.toString() ?: return
            val off = et.startOffset
            val selStart = off + et.selectionStart
            val selEnd = off + et.selectionEnd
            if (selAnchor < 0) selAnchor = selStart
            val moving = if (selStart == selAnchor) selEnd else selStart
            val next = when (code) {
                KeyCode.ARROW_LEFT -> (moving - 1).coerceAtLeast(0)
                KeyCode.ARROW_RIGHT -> (moving + 1).coerceAtMost(off + text.length)
                KeyCode.ARROW_UP -> lineMove(text, moving - off, -1) + off
                KeyCode.ARROW_DOWN -> lineMove(text, moving - off, +1) + off
                else -> moving
            }
            ic.setSelection(minOf(selAnchor, next), maxOf(selAnchor, next))
        }
    }

    /** Cursor position one line up/down, keeping the column where possible. */
    private fun lineMove(text: String, pos: Int, dir: Int): Int {
        val p = pos.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', p - 1) + 1
        val column = p - lineStart
        return if (dir < 0) {
            if (lineStart == 0) 0 else {
                val prevStart = text.lastIndexOf('\n', lineStart - 2) + 1
                (prevStart + column).coerceAtMost(lineStart - 1)
            }
        } else {
            val lineEnd = text.indexOf('\n', p).let { if (it < 0) text.length else it }
            if (lineEnd >= text.length) text.length else {
                val nextStart = lineEnd + 1
                val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
                (nextStart + column).coerceAtMost(nextEnd)
            }
        }
    }

    // ---- number row arming: digits are always visible but inert until the
    // "123" key arms them; each digit press restarts the 5-second window ----

    private val numHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val numDisarm = Runnable {
        if (numbersArmed) {
            numbersArmed = false
            reloadKeyboard()
        }
    }

    private fun toggleNumbersArmed() {
        numHandler.removeCallbacks(numDisarm)
        numbersArmed = !numbersArmed
        if (numbersArmed) numHandler.postDelayed(numDisarm, 5000)
        reloadKeyboard()
    }

    /** A digit was typed: the 5-second disarm window restarts. */
    fun touchNumberTimer() {
        if (!numbersArmed) return
        numHandler.removeCallbacks(numDisarm)
        numHandler.postDelayed(numDisarm, 5000)
    }

    /** Real window hide: drop the armed state without a hide/show cycle. */
    fun disarmNumbersQuietly() {
        if (!numbersArmed) return
        numHandler.removeCallbacks(numDisarm)
        numbersArmed = false
        // Flag only - the layout rebuilds right before the IME is next shown.
        helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().markThemeNeedsReload()
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

    private fun fieldText(): String {
        val ic = ime.currentInputConnection ?: return ""
        val extracted = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
        return extracted?.text?.toString().orEmpty()
    }

    /** The current selection, or null - selected text takes priority everywhere. */
    private fun selectedText(): String? = runCatching {
        ime.currentInputConnection?.getSelectedText(0)?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun clipboardText(): String = runCatching {
        val cm = ime.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    }.getOrDefault("")

    /** Selection > field > clipboard - what assist actions work on. */
    private fun assistSource(): Pair<String, String>? {
        selectedText()?.let { return it to "выделение" }
        fieldText().takeIf { it.isNotBlank() }?.let { return it to "поле" }
        clipboardText().takeIf { it.isNotBlank() }?.let { return it to "буфер" }
        return null
    }

    /** The "П" hub: the panel shows the field text and EVERY action. */
    private fun showFixHub() {
        if (busy) { toast("Уже работаю…"); return }
        if (session != null) { stopDictation(); return }
        if (overlay.isShowing) { overlay.hide(); return }  // second tap closes
        val sel = selectedText()
        val text = sel ?: fieldText()
        // The panel scrolls, so show plenty - but not an unbounded field.
        val preview = when {
            text.isBlank() -> "Поле пустое. Диктовка и действия с буфером доступны."
            sel != null -> "— выделенный фрагмент —\n" + text.take(4000)
            else -> text.takeLast(4000)
        }
        overlay.show(
            preview,
            listOf(
                PravkaOverlay.Button("Почистить", big = true) { cleanField("", strong = false) },
                PravkaOverlay.Button("Причесать") { cleanField(redo(PravkaPromptStore.PromptId.REDO_POLISH), strong = true) },
                PravkaOverlay.Button("Короче") { cleanField(redo(PravkaPromptStore.PromptId.REDO_SHORTER), strong = true) },
                PravkaOverlay.Button("Длиннее") { cleanField(redo(PravkaPromptStore.PromptId.REDO_LONGER), strong = true) },
                PravkaOverlay.Button("Диктовка") { overlay.hide(); toggleDictation() },
                PravkaOverlay.Button("Отменить") {
                    overlay.hide()
                    ime.onCodeInput(KeyCode.UNDO, helium314.keyboard.latin.common.Constants.SUGGESTION_STRIP_COORDINATE,
                        helium314.keyboard.latin.common.Constants.SUGGESTION_STRIP_COORDINATE, false)
                },
                PravkaOverlay.Button("Коротко") { runAssist(redo(PravkaPromptStore.PromptId.ASSIST_SUMMARY), insertResult = false) },
                PravkaOverlay.Button("Ответить") { runAssist(redo(PravkaPromptStore.PromptId.ASSIST_REPLY), insertResult = false) },
                PravkaOverlay.Button("Перевод") { runAssist(redo(PravkaPromptStore.PromptId.ASSIST_TRANSLATE), insertResult = false) },
                PravkaOverlay.Button("История") {
                    openSettings(helium314.keyboard.settings.SettingsDestination.PravkaHistory)
                },
                PravkaOverlay.Button("Закрыть") { overlay.hide() },
            ),
        )
    }

    // ---- assist actions: summarize / reply / translate (field or clipboard) ----

    private fun runAssist(instruction: String, insertResult: Boolean) {
        if (busy) { toast("Уже работаю…"); return }
        val source = assistSource() ?: run { toast("Нет текста ни в поле, ни в буфере."); return }
        val (content, from) = source
        val key = apiKey()
        if (key.isBlank()) { toast("Нет API-ключа: Настройки клавиатуры → Правка."); return }
        busy = true
        overlay.show("… (текст из: $from)", buttons = emptyList())
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PravkaApi.assist(
                    apiKey = key,
                    instruction = instruction,
                    content = content,
                    onDelta = { partial -> scope.launch { overlay.update(partial.takeLast(1500)) } },
                )
            }
            busy = false
            result.onSuccess { fix ->
                PravkaStore.appendHistory(ime, "assist", fix, content.take(2000), fix.text, true, null)
                copyToClipboard(fix.text)
                if (insertResult) {
                    overlay.hide()
                    insertAtCursor(fix.text)
                } else {
                    // Result stays on the panel for reading; insert on demand.
                    overlay.show(
                        fix.text,
                        listOf(
                            PravkaOverlay.Button("Вставить", big = true) {
                                overlay.hide()
                                insertAtCursor(fix.text)
                            },
                            PravkaOverlay.Button("Скопировано ✓") { },
                            PravkaOverlay.Button("Закрыть") { overlay.hide() },
                        ),
                    )
                }
            }.onFailure { e ->
                overlay.hide()
                toast(e.message ?: "Ошибка")
            }
        }
    }

    // Whole-field fixes are capped: past this, ask for a selection instead of
    // silently truncating (a truncated rewrite would eat the rest of the field).
    private val maxFixLen = 15000

    private fun cleanField(directive: String, strong: Boolean) {
        if (busy) { toast("Уже работаю…"); return }
        if (session != null) { stopDictation(); return }
        // A selection narrows the fix to just that fragment.
        val sel = selectedText()
        val text = sel ?: fieldText()
        if (text.isBlank()) { toast("Поле пустое — нечего править."); return }
        if (text.length > maxFixLen) {
            toast("Текст слишком длинный (${text.length} зн.). Выдели фрагмент — Правка обработает только его.")
            return
        }
        val key = apiKey()
        if (key.isBlank()) { toast("Нет API-ключа: Настройки клавиатуры → Правка."); return }

        busy = true
        overlay.show(if (strong) "…" else text.takeLast(400), buttons = emptyList())
        scope.launch {
            val prepared = withContext(Dispatchers.IO) { PravkaStore.prepare(ime, text) }
            val result = withContext(Dispatchers.IO) {
                PravkaApi.proofreadFull(
                    apiKey = key,
                    input = prepared.text,
                    directive = directive,
                    model = if (strong) PravkaApi.MODEL_OPUS else PravkaApi.MODEL_SONNET,
                    onDelta = { partial -> scope.launch { overlay.update(partial.takeLast(1200)) } },
                    dictBlock = prepared.dictBlock,
                    cleanTemplate = redo(PravkaPromptStore.PromptId.CLEAN),
                )
            }
            busy = false
            overlay.hide()
            result.onSuccess { fix ->
                val cleaned = fix.text
                val changed = cleaned.trim() != text.trim()
                PravkaStore.appendHistory(ime, "keyboard", fix, text, cleaned, changed, null)
                if (!changed) {
                    toast("Без изменений")
                } else {
                    if (sel != null) replaceSelection(cleaned) else replaceWholeField(cleaned)
                    copyToClipboard(cleaned)
                }
            }.onFailure { e ->
                PravkaStore.appendHistory(ime, "keyboard", null, text, "", false, e.message)
                toast(e.message ?: "Ошибка Правки")
            }
        }
    }

    /** commitText replaces whatever is currently selected in the editor. */
    private fun replaceSelection(newText: String) {
        val ic = ime.currentInputConnection ?: return
        runCatching {
            ic.beginBatchEdit()
            ic.finishComposingText()
            ic.commitText(newText, 1)
            ic.endBatchEdit()
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

    /** Toolbar mic: the panel opens first with a big "Начать". */
    private fun showDictationLobby() {
        if (overlay.isShowing) { overlay.hide(); return }  // second tap closes
        overlay.show(
            "Диктовка: нажми «Начать» и говори.",
            listOf(
                PravkaOverlay.Button("Отмена") { overlay.hide() },
                PravkaOverlay.Button("Начать", big = true) { toggleDictation() },
            ),
        )
    }

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

        val s = GoogleSpeechSession(
            ime,
            biasing = PravkaStore.biasingWords(ime),
            formatting = prefs().getBoolean("speech_formatting", true),
            segmentedSession = prefs().getBoolean("speech_segmented", true),
        )
        session = s
        pendingDirective = ""
        discardTake = false
        overlay.show(
            "Говори…",
            listOf(
                PravkaOverlay.Button("Причесать") { pendingDirective = redo(PravkaPromptStore.PromptId.REDO_POLISH); stopDictation() },
                PravkaOverlay.Button("■  Закончить", big = true) { stopDictation() },
                PravkaOverlay.Button("Короче") { pendingDirective = redo(PravkaPromptStore.PromptId.REDO_SHORTER); stopDictation() },
                PravkaOverlay.Button("✕ Отмена") { discardTake = true; stopDictation() },
            ),
        )
        s.start(
            onPartial = { live -> overlay.update(live.takeLast(1200)) },
            onCheckpoint = { },
            onDone = { text -> onDictationDone(text) },
            onError = { msg ->
                session = null
                overlay.hide()
                toast(msg)
            },
            onLog = { line -> PravkaStore.logEvent(ime, "kbd $line") },
        )
    }

    private fun stopDictation() {
        session?.stop()
    }

    // "✕ Отмена" on the dictation panel: throw the take away entirely.
    private var discardTake = false

    private fun onDictationDone(rawText: String) {
        session = null
        if (discardTake) {
            discardTake = false
            overlay.hide()
            toast("Отменено")
            return
        }
        overlay.setButtons(emptyList())
        val text = VoiceCommands.apply(rawText)
        PravkaStore.logEvent(ime, "kbd take raw=${rawText.length} clean=${text.length}")
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
            val prepared = withContext(Dispatchers.IO) { PravkaStore.prepare(ime, text) }
            val result = withContext(Dispatchers.IO) {
                PravkaApi.proofreadFull(
                    apiKey = key,
                    input = prepared.text,
                    directive = directive,
                    contextBefore = context,
                    model = if (directive.isBlank()) PravkaApi.MODEL_SONNET else PravkaApi.MODEL_OPUS,
                    onDelta = { partial -> scope.launch { overlay.update(partial.takeLast(1200)) } },
                    dictBlock = prepared.dictBlock,
                    cleanTemplate = redo(PravkaPromptStore.PromptId.CLEAN),
                )
            }
            busy = false
            overlay.hide()
            val final = result.getOrNull()?.text ?: text  // never lose the words
            PravkaStore.logEvent(
                ime,
                "kbd insert len=${final.length} fixed=${result.isSuccess} ic=${ime.currentInputConnection != null}",
            )
            insertAtCursor(final)
            copyToClipboard(final)
            result.onSuccess { fix ->
                PravkaStore.appendHistory(ime, "keyboard", fix, text, fix.text, fix.text.trim() != text.trim(), null)
            }
            result.onFailure { e ->
                PravkaStore.appendHistory(ime, "keyboard", null, text, "", false, e.message)
                toast("Вставил без чистки: ${e.message}")
            }
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
