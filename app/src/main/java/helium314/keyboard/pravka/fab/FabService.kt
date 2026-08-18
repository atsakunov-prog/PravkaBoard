// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.pravka.fab

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import helium314.keyboard.pravka.GoogleSpeechSession
import helium314.keyboard.pravka.PravkaApi
import helium314.keyboard.pravka.VoiceCommands
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Pravka floating button, merged into the keyboard app: for the moments
// when there IS no keyboard on screen (reading a PDF, a viewer app) - short
// tap dictates into the focused field, long press proofreads it. Shares the
// API key, prompts, dictation engine and voice commands with the keyboard.
class FabService : AccessibilityService() {

    companion object {
        var instance: FabService? = null
            private set
        private const val PREFS = "pravka"
        private const val KEY_API = "pravka_api_key"
    }

    private val crashLogger = CoroutineExceptionHandler { _, e ->
        toast("Правка: ${e.javaClass.simpleName} ${e.message ?: ""}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashLogger)

    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var button: FrameLayout? = null
    private var buttonBg: GradientDrawable? = null
    private var glyph: android.widget.ImageView? = null
    private var recDot: android.view.View? = null
    private var progress: android.widget.ProgressBar? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var ticker: FrameLayout? = null
    private var tickerText: TextView? = null
    private var tickerVisible = false
    private var busy = false
    private var recording = false
    private var session: GoogleSpeechSession? = null
    private var cachedFocus: WeakReference<AccessibilityNodeInfo>? = null
    private var dictationTarget: WeakReference<AccessibilityNodeInfo>? = null

    // Editorial palette shared with the Pravka app's button and launcher icon:
    // orange circle, paper-white wide "П"; deep red while recording.
    private val accent = 0xFFEA580C.toInt()
    private val recRed = 0xFFD8342A.toInt()
    private val paper = 0xFFF7F3EA.toInt()
    private var idleAlpha = 0.35f
    private var buttonSize = 0  // px, set in createButton from prefs
    private val tickerAlpha = 0.82f  // near-opaque, 0.6 was too see-through
    private val tickerLines = 4

    /** Re-reads size/alpha prefs and applies them live (settings sliders). */
    fun applyLook() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        idleAlpha = prefs.getFloat("fab_alpha", 0.35f)
        buttonSize = dpT(prefs.getInt("fab_size", 48))
        buttonParams?.let { p ->
            p.width = buttonSize
            p.height = buttonSize
            button?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }
        if (!busy && !recording) button?.alpha = idleAlpha
    }

    private fun apiKey(): String =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, "").orEmpty()

    private fun toast(msg: String) =
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()

    // ---- lifecycle ----

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        createButton()
    }

    override fun onDestroy() {
        instance = null
        runCatching { session?.stop() }
        button?.let { runCatching { windowManager.removeView(it) } }
        button = null
        ticker?.let { runCatching { windowManager.removeView(it) } }
        ticker = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source ?: return
                if (source.isEditable) cachedFocus = WeakReference(source)
            }
        }
    }

    private fun focusedEditableNode(): AccessibilityNodeInfo? {
        runCatching {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isEditable }?.let { return it }
        }
        val cached = cachedFocus?.get() ?: return null
        return runCatching { if (cached.refresh() && cached.isEditable) cached else null }.getOrNull()
    }

    // ---- the button ----

    @SuppressLint("ClickableViewAccessibility")
    private fun createButton() {
        if (button != null) return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        buttonSize = dp(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("fab_size", 48))
        idleAlpha = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("fab_alpha", 0.35f)
        val size = buttonSize

        val container = FrameLayout(this)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
        }
        buttonBg = bg
        container.background = bg
        container.elevation = dp(4).toFloat()
        container.alpha = idleAlpha
        glyph = android.widget.ImageView(this).apply {
            setImageResource(helium314.keyboard.latin.R.drawable.ic_pravka_fab_glyph)
        }
        container.addView(
            glyph,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        // White square "stop" glyph, shown only while recording.
        recDot = android.view.View(this).apply {
            visibility = android.view.View.GONE
            background = GradientDrawable().apply {
                setColor(paper)
                cornerRadius = dp(3).toFloat()
            }
        }
        container.addView(recDot, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        progress = android.widget.ProgressBar(this).apply {
            visibility = android.view.View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(paper)
        }
        container.addView(progress, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            // Free positioning, remembered where the owner last dropped it.
            val xf = prefs.getFloat("fab_x", 0.97f)
            val yf = prefs.getFloat("fab_y", 0.45f)
            x = ((dm.widthPixels - size) * xf).toInt()
            y = ((dm.heightPixels - size) * yf).toInt()
        }

        // Drag + tap + long-press, self-contained.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var downAt = 0L
        val longPress = Runnable { if (!moved && !busy) onLongPress() }
        container.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false; downAt = System.currentTimeMillis()
                    v.postDelayed(longPress, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || dx * dx + dy * dy > 20 * 20) {
                        moved = true
                        v.removeCallbacks(longPress)
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(container, params) }
                        repositionTicker()  // the pill rides along
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (moved) {
                        val dm = resources.displayMetrics
                        params.x = params.x.coerceIn(0, dm.widthPixels - buttonSize)
                        params.y = params.y.coerceIn(0, dm.heightPixels - buttonSize)
                        runCatching { windowManager.updateViewLayout(container, params) }
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putFloat("fab_x", params.x.toFloat() / (dm.widthPixels - buttonSize).coerceAtLeast(1))
                            .putFloat("fab_y", params.y.toFloat() / (dm.heightPixels - buttonSize).coerceAtLeast(1))
                            .apply()
                    } else if (System.currentTimeMillis() - downAt < 450) onTap()
                    true
                }
                else -> { v.removeCallbacks(longPress); true }
            }
        }

        buttonParams = params
        button = container
        runCatching { windowManager.addView(container, params) }
    }

    /** Busy (API round trip): white spinner in the circle. */
    private fun setBusyLook(value: Boolean) {
        glyph?.visibility = if (value || recording) android.view.View.GONE else android.view.View.VISIBLE
        progress?.visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
        button?.alpha = if (value || recording) 1f else idleAlpha
    }

    /** Recording: deep red circle with a white stop square, full opacity. */
    private fun setRecording(value: Boolean) {
        recording = value
        buttonBg?.setColor(if (value) recRed else accent)
        recDot?.visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
        glyph?.visibility = if (value || busy) android.view.View.GONE else android.view.View.VISIBLE
        button?.alpha = if (value || busy) 1f else idleAlpha
    }

    // ---- Live-dictation ticker (telegraph): an orange pill beside the
    // button where recognized words crawl by, teleprompter-style ----

    private fun dpT(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun tickerWidth(): Int = (if (buttonSize > 0) buttonSize else dpT(48)) * 6  // six diameters
    private fun tickerHeight(): Int = dpT(tickerLines * 24 + 16)

    private var tickerParams: WindowManager.LayoutParams? = null
    private var lastTickerText = ""
    private var lastTickerAt = 0L

    private fun showTicker() {
        if (ticker == null) createTicker()
        repositionTicker()
        val t = ticker ?: return
        tickerText?.text = ""
        lastTickerText = ""
        lastTickerAt = 0L
        // Long takes happen without touches - don't let the screen sleep while
        // the ticker (dictation / streaming fix) is up. Cleared on hide.
        tickerParams?.flags = (tickerParams?.flags ?: 0) or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        runCatching { windowManager.updateViewLayout(t, tickerParams) }
        if (!tickerVisible) {
            tickerVisible = true
            t.visibility = android.view.View.VISIBLE
            t.alpha = 0f
            t.animate().alpha(tickerAlpha).setDuration(180).start()
        }
    }

    private fun createTicker() {
        val pill = FrameLayout(this)
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpT(24).toFloat()
            setColor(accent)
        }
        pill.elevation = dpT(4).toFloat()
        val tv = TextView(this).apply {
            setTextColor(paper)
            textSize = 17f
            maxLines = tickerLines
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dpT(16), dpT(8), dpT(16), dpT(8))
            setLineSpacing(0f, 1.05f)
        }
        tickerText = tv
        pill.addView(
            tv,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        val p = WindowManager.LayoutParams(
            tickerWidth(), tickerHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        tickerParams = p
        ticker = pill
        runCatching { windowManager.addView(pill, p) }
        pill.visibility = android.view.View.GONE
    }

    /** Beside the button, on the side with room; vertically centred on it. */
    private fun repositionTicker() {
        val bp = buttonParams ?: return
        val tp = tickerParams ?: return
        val dm = resources.displayMetrics
        val size = if (buttonSize > 0) buttonSize else dpT(48)
        val w = tickerWidth()
        val h = tickerHeight()
        tp.y = (bp.y - (h - size) / 2).coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
        tp.x = if (bp.x + size / 2 < dm.widthPixels / 2) bp.x + size + dpT(8) else bp.x - w - dpT(8)
        tp.x = tp.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        if (tickerVisible) ticker?.let { runCatching { windowManager.updateViewLayout(it, tp) } }
    }

    private fun updateTicker(text: String) {
        val tv = tickerText ?: return
        // Partials arrive several times a second; cap the refresh rate and skip
        // identical text so the overlay doesn't compete with recognition.
        val tail = text.takeLast(400)
        if (tail == lastTickerText) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTickerAt < 120) return
        lastTickerAt = now
        lastTickerText = tail
        tv.text = tail
        // ellipsize=START is ignored on multi-line TextViews: trim leading lines
        // after layout so the newest words are what stays visible.
        tv.post {
            val layout = tv.layout ?: return@post
            if (layout.lineCount > tickerLines) {
                val cut = layout.getLineStart(layout.lineCount - tickerLines)
                val current = tv.text?.toString() ?: return@post
                if (cut in 1 until current.length) tv.text = current.substring(cut)
            }
        }
    }

    private fun hideTicker() {
        val t = ticker ?: return
        if (!tickerVisible) return
        tickerVisible = false
        t.animate().alpha(0f).setDuration(220).withEndAction {
            // A hide -> immediate re-show (dictation ends, CLEAN streaming
            // starts) cancels this fade; don't hide what just came back.
            if (!tickerVisible) {
                t.visibility = android.view.View.GONE
                tickerParams?.let { p ->
                    p.flags = p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                    runCatching { windowManager.updateViewLayout(t, p) }
                }
            }
        }.start()
    }

    // ---- haptics (API-21-safe) ----

    private fun vibrate(pattern: LongArray) {
        runCatching {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun hapticStart() = vibrate(longArrayOf(0, 35))
    private fun hapticSuccess() = vibrate(longArrayOf(0, 35, 90, 35))
    private fun hapticError() = vibrate(longArrayOf(0, 300))

    // ---- actions ----

    private fun onTap() {
        val s = session
        if (s != null) { s.stop(); return }
        if (busy) { toast("Уже работаю…"); return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(
                Intent(this, helium314.keyboard.pravka.MicPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        if (!GoogleSpeechSession.isAvailable(this)) { toast("Распознавание речи недоступно"); return }

        val fabPrefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val newSession = GoogleSpeechSession(
            this,
            biasing = helium314.keyboard.pravka.PravkaStore.biasingWords(this),
            formatting = fabPrefs.getBoolean("speech_formatting", true),
            segmentedSession = fabPrefs.getBoolean("speech_segmented", false),
        )
        session = newSession
        showTicker()
        updateTicker("Говори…")
        setRecording(true)
        newSession.start(
            onReady = { hapticStart() },
            onPartial = { live -> updateTicker(live) },
            onCheckpoint = { },
            onDone = { text -> onDictationDone(text) },
            onError = { msg ->
                session = null
                hideTicker()
                setRecording(false)
                hapticError()
                toast(msg)
            },
            onLog = { line -> helium314.keyboard.pravka.PravkaStore.logEvent(this, "fab $line") },
        )
        dictationTarget = focusedEditableNode()?.let { WeakReference(it) }
    }

    private fun onLongPress() = cleanFocusedField()

    // ---- dictation delivery ----

    private fun onDictationDone(rawText: String) {
        session = null
        setRecording(false)
        val text = VoiceCommands.apply(rawText)
        if (text.isBlank()) {
            hideTicker(); setBusyLook(false); hapticError(); toast("Ничего не расслышал")
            return
        }
        setBusyLook(true)
        scope.launch {
            val node = dictationTarget?.get()?.takeIf { runCatching { it.refresh() && it.isEditable }.getOrDefault(false) }
                ?: focusedEditableNode()
            val key = apiKey()
            val final = if (key.isBlank()) text else {
                updateTicker("…")
                val contextBefore = node?.let { n ->
                    runCatching {
                        val existing = n.effectiveTextFab()
                        val cursor = n.textSelectionEnd.takeIf { it in 1..existing.length } ?: existing.length
                        existing.substring(maxOf(0, cursor - 300), cursor)
                    }.getOrDefault("")
                }.orEmpty()
                withContext(Dispatchers.IO) {
                    val prepared = helium314.keyboard.pravka.PravkaStore.prepare(this@FabService, text)
                    PravkaApi.proofreadFull(
                        apiKey = key, input = prepared.text, contextBefore = contextBefore,
                        onDelta = { partial -> scope.launch { updateTicker(partial) } },
                        dictBlock = prepared.dictBlock,
                        cleanTemplate = helium314.keyboard.pravka.PravkaPromptStore.effective(
                            this@FabService, helium314.keyboard.pravka.PravkaPromptStore.PromptId.CLEAN),
                    )
                }.onSuccess { fix ->
                    helium314.keyboard.pravka.PravkaStore.appendHistory(
                        this@FabService, "fab", fix, text, fix.text, fix.text.trim() != text.trim(), null)
                }.onFailure { e ->
                    helium314.keyboard.pravka.PravkaStore.appendHistory(
                        this@FabService, "fab", null, text, "", false, e.message)
                }.map { it.text }.getOrElse { e -> toast("Вставил без чистки: ${e.message}"); text }
            }
            hideTicker()
            setBusyLook(false)
            copyToClipboard(final)
            if (node == null || !insertAtCursor(node, final)) {
                toast("Поле не нашлось — текст в буфере обмена")
            } else {
                hapticSuccess()
            }
        }
    }

    private fun insertAtCursor(node: AccessibilityNodeInfo, text: String): Boolean = runCatching {
        val existing = node.effectiveTextFab()
        val selEnd = node.textSelectionEnd
        val cursor = if (selEnd in 1..existing.length) selEnd else existing.length
        val needsSpace = cursor > 0 && !existing[cursor - 1].isWhitespace()
        val newText = existing.substring(0, cursor) + (if (needsSpace) " " else "") + text + existing.substring(cursor)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }.getOrDefault(false)

    // ---- long press: proofread the whole focused field ----

    private fun cleanFocusedField() {
        if (busy || session != null) return
        val node = focusedEditableNode() ?: run { toast("Нет поля с текстом"); return }
        val text = runCatching { node.effectiveTextFab() }.getOrDefault("")
        if (text.isBlank()) { toast("Поле пустое — нечего править."); return }
        val key = apiKey()
        if (key.isBlank()) { toast("Нет API-ключа: настройки PravkaBoard → Правка."); return }
        busy = true
        setBusyLook(true)
        hapticStart()
        showTicker()
        updateTicker(text.takeLast(400))
        val pinned = WeakReference(node)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val prepared = helium314.keyboard.pravka.PravkaStore.prepare(this@FabService, text)
                PravkaApi.proofreadFull(
                    apiKey = key, input = prepared.text,
                    onDelta = { partial -> scope.launch { updateTicker(partial) } },
                    dictBlock = prepared.dictBlock,
                    cleanTemplate = helium314.keyboard.pravka.PravkaPromptStore.effective(
                        this@FabService, helium314.keyboard.pravka.PravkaPromptStore.PromptId.CLEAN),
                ).map { fix ->
                    helium314.keyboard.pravka.PravkaStore.appendHistory(
                        this@FabService, "fab", fix, text, fix.text, fix.text.trim() != text.trim(), null)
                    fix.text
                }.onFailure { e ->
                    helium314.keyboard.pravka.PravkaStore.appendHistory(
                        this@FabService, "fab", null, text, "", false, e.message)
                }
            }
            busy = false
            hideTicker()
            setBusyLook(false)
            result.onSuccess { cleaned ->
                if (cleaned.trim() == text.trim()) { toast("Без изменений"); return@onSuccess }
                copyToClipboard(cleaned)
                val n = pinned.get()?.takeIf { runCatching { it.refresh() && it.isEditable }.getOrDefault(false) }
                val current = n?.let { runCatching { it.effectiveTextFab() }.getOrDefault(null) }
                // Never overwrite text that changed during the round trip.
                val ok = n != null && current != null &&
                    current.replace(Regex("\\s+"), " ").trim() == text.replace(Regex("\\s+"), " ").trim() &&
                    runCatching {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleaned)
                        }
                        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }.getOrDefault(false)
                if (ok) hapticSuccess() else toast("Не смог записать в поле — результат в буфере")
            }.onFailure { e -> hapticError(); toast(e.message ?: "Ошибка Правки") }
        }
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Правка", text))
        }
    }
}

// Placeholders some apps report as the field's *text* while it is empty
// (ported from the Pravka app - both insert and clean must agree on this).
private val COMMON_PLACEHOLDERS = setOf(
    "сообщение", "сообщение…", "сообщение...",
    "введите сообщение", "напишите сообщение", "написать сообщение",
    "message", "type a message", "aa",
    "поиск", "search", "введите текст", "текст сообщения",
)

fun AccessibilityNodeInfo.effectiveTextFab(): String {
    val raw = text?.toString().orEmpty()
    if (raw.isEmpty() || isShowingHintText) return if (isShowingHintText) "" else raw
    val trimmed = raw.trim()
    val hint = hintText?.toString()?.trim()
    val description = contentDescription?.toString()?.trim()
    val isPlaceholder = (!hint.isNullOrEmpty() && trimmed.equals(hint, ignoreCase = true)) ||
        (!description.isNullOrEmpty() && trimmed.equals(description, ignoreCase = true)) ||
        trimmed.lowercase() in COMMON_PLACEHOLDERS
    return if (isPlaceholder) "" else raw
}
