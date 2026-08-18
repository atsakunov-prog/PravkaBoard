// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.pravka.fab

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
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
    private var buttonParams: WindowManager.LayoutParams? = null
    private var ticker: TextView? = null
    private var busy = false
    private var session: GoogleSpeechSession? = null
    private var cachedFocus: WeakReference<AccessibilityNodeInfo>? = null
    private var dictationTarget: WeakReference<AccessibilityNodeInfo>? = null

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
        val size = dp(48)

        val container = FrameLayout(this)
        container.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFEA580C.toInt())
        }
        container.alpha = 0.4f
        container.addView(
            TextView(this).apply {
                text = "П"
                setTextColor(0xFFF7F3EA.toInt())
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            x = (dm.widthPixels * 0.92f).toInt() - size
            y = (dm.heightPixels * 0.45f).toInt()
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
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (!moved && System.currentTimeMillis() - downAt < 450) onTap()
                    true
                }
                else -> { v.removeCallbacks(longPress); true }
            }
        }

        buttonParams = params
        button = container
        runCatching { windowManager.addView(container, params) }
    }

    private fun setBusyLook(value: Boolean) {
        button?.alpha = if (value) 1f else 0.4f
    }

    // ---- ticker pill next to the button ----

    private fun showTicker() {
        if (ticker != null) { ticker?.text = ""; return }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val bp = buttonParams ?: return
        val tv = TextView(this).apply {
            setTextColor(0xFFF7F3EA.toInt())
            textSize = 15f
            maxLines = 4
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xF5241F19.toInt())
            }
        }
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.6f).toInt()
        val params = WindowManager.LayoutParams(
            w, dp(110),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (bp.x > dm.widthPixels / 2) (bp.x - w - dp(8)).coerceAtLeast(0) else bp.x + dp(56)
            y = bp.y.coerceIn(0, (dm.heightPixels - dp(120)).coerceAtLeast(0))
        }
        ticker = tv
        runCatching { windowManager.addView(tv, params) }
    }

    private fun updateTicker(text: String) {
        ticker?.text = text.takeLast(400)
    }

    private fun hideTicker() {
        ticker?.let { runCatching { windowManager.removeView(it) } }
        ticker = null
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

        val newSession = GoogleSpeechSession(this)
        session = newSession
        showTicker()
        updateTicker("Говори…")
        setBusyLook(true)
        newSession.start(
            onReady = { hapticStart() },
            onPartial = { live -> updateTicker(live) },
            onCheckpoint = { },
            onDone = { text -> onDictationDone(text) },
            onError = { msg ->
                session = null
                hideTicker()
                setBusyLook(false)
                hapticError()
                toast(msg)
            },
        )
        dictationTarget = focusedEditableNode()?.let { WeakReference(it) }
    }

    private fun onLongPress() = cleanFocusedField()

    // ---- dictation delivery ----

    private fun onDictationDone(rawText: String) {
        session = null
        val text = VoiceCommands.apply(rawText)
        if (text.isBlank()) {
            hideTicker(); setBusyLook(false); hapticError(); toast("Ничего не расслышал")
            return
        }
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
                    PravkaApi.proofread(
                        apiKey = key, input = text, contextBefore = contextBefore,
                        onDelta = { partial -> scope.launch { updateTicker(partial) } },
                    )
                }.getOrElse { e -> toast("Вставил без чистки: ${e.message}"); text }
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
                PravkaApi.proofread(
                    apiKey = key, input = text,
                    onDelta = { partial -> scope.launch { updateTicker(partial) } },
                )
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
