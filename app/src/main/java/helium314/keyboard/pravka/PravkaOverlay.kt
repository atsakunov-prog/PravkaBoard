// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.pravka

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.keyboard.KeyboardSwitcher

// The Pravka surface: covers the key area with a dark panel that shows text
// (live dictation, streaming fixes) and a configurable button row - the
// panel is the hub for starting/stopping dictation and one-tap rework
// actions. The toolbar above stays usable.
class PravkaOverlay {

    class Button(val label: String, val big: Boolean = false, val onClick: () -> Unit)

    private var panel: LinearLayout? = null
    private var textView: TextView? = null
    private var scroll: ScrollView? = null
    private var buttonRow: LinearLayout? = null

    /** Tap on the text area (used as "stop" during a live take). */
    var onTextTap: (() -> Unit)? = null

    private fun host(): ViewGroup? =
        KeyboardSwitcher.getInstance().mainKeyboardView?.parent as? ViewGroup

    private fun dp(parent: ViewGroup, v: Int) =
        (v * parent.context.resources.displayMetrics.density).toInt()

    private fun pill(parent: ViewGroup, b: Button): TextView =
        TextView(parent.context).apply {
            text = b.label
            setTextColor(0xFFF7F3EA.toInt())
            textSize = if (b.big) 16f else 13f
            typeface = if (b.big) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(parent, 22).toFloat()
                setColor(if (b.big) 0xFFEA580C.toInt() else 0x33FFFFFF)
            }
            setPadding(dp(parent, if (b.big) 26 else 13), dp(parent, 11), dp(parent, if (b.big) 26 else 13), dp(parent, 11))
            setOnClickListener { b.onClick() }
        }

    @SuppressLint("ClickableViewAccessibility")
    fun show(hint: String, buttons: List<Button> = emptyList()) {
        if (panel == null) build()
        update(hint)
        setButtons(buttons)
    }

    private fun build() {
        val parent = host() ?: return
        val tv = TextView(parent.context).apply {
            setTextColor(0xFFF7F3EA.toInt())
            textSize = 18f
            setLineSpacing(0f, 1.15f)
            val pad = dp(parent, 16)
            setPadding(pad, pad, pad, pad)
        }
        val sc = ScrollView(parent.context).apply {
            isFillViewport = true
            addView(
                tv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
            setOnClickListener { onTextTap?.invoke() }
        }
        val btnRow = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = dp(parent, 8)
            // The owner found the buttons glued to the very bottom edge -
            // keep them comfortably above it.
            setPadding(pad, pad, pad, dp(parent, 28))
        }
        val column = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF5241F19.toInt())  // near-opaque ink
            addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        textView = tv
        scroll = sc
        buttonRow = btnRow
        panel = column
        runCatching {
            parent.addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    fun setButtons(buttons: List<Button>) {
        val area = buttonRow ?: return
        val parent = area.parent as? ViewGroup ?: return
        area.removeAllViews()
        // Wrap into centered rows so a big hub stays reachable by thumb.
        buttons.chunked(4).forEach { chunk ->
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            chunk.forEach { b ->
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(parent, 5); marginEnd = dp(parent, 5)
                    topMargin = dp(parent, 4); bottomMargin = dp(parent, 4)
                }
                row.addView(pill(parent, b), lp)
            }
            area.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    fun update(text: String) {
        val tv = textView ?: return
        tv.text = text
        // Teleprompter: keep the newest words visible.
        scroll?.post { scroll?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    fun hide() {
        val p = panel ?: return
        panel = null
        textView = null
        scroll = null
        buttonRow = null
        runCatching { (p.parent as? ViewGroup)?.removeView(p) }
    }

    val isShowing: Boolean get() = panel != null
}
