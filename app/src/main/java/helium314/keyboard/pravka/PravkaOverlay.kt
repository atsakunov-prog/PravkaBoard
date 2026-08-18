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
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardSwitcher

// The dictation surface: while a take (or a streaming fix) is live, the key
// area is covered by a dark panel where the text appears - "the keyboard
// becomes the ticker". A button row sits at the bottom: a big STOP in the
// middle plus one-tap finishers that stop AND apply a style pass in one go.
class PravkaOverlay {

    private var panel: LinearLayout? = null
    private var textView: TextView? = null
    private var scroll: ScrollView? = null
    private var buttons: LinearLayout? = null

    /** Plain stop (also fired by tapping the text area). */
    var onStop: (() -> Unit)? = null

    /** Stop and run this extra directive after the CLEAN pass. */
    var onStopWith: ((directive: String) -> Unit)? = null

    private fun host(): ViewGroup? =
        KeyboardSwitcher.getInstance().mainKeyboardView?.parent as? ViewGroup

    private fun dp(parent: ViewGroup, v: Int) =
        (v * parent.context.resources.displayMetrics.density).toInt()

    private fun pill(parent: ViewGroup, label: String, big: Boolean, onClick: () -> Unit): TextView =
        TextView(parent.context).apply {
            text = label
            setTextColor(0xFFF7F3EA.toInt())
            textSize = if (big) 16f else 13f
            typeface = if (big) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(parent, 20).toFloat()
                setColor(if (big) 0xFFEA580C.toInt() else 0x33FFFFFF)
            }
            setPadding(dp(parent, if (big) 22 else 12), dp(parent, 10), dp(parent, if (big) 22 else 12), dp(parent, 10))
            setOnClickListener { onClick() }
        }

    @SuppressLint("ClickableViewAccessibility")
    fun show(hint: String) {
        if (panel != null) { update(hint); return }
        val parent = host() ?: return
        val tv = TextView(parent.context).apply {
            setTextColor(0xFFF7F3EA.toInt())
            textSize = 18f
            setLineSpacing(0f, 1.15f)
            val pad = dp(parent, 16)
            setPadding(pad, pad, pad, pad)
            text = hint
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
            setOnClickListener { onStop?.invoke() }
        }
        val btnRow = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = dp(parent, 8)
            setPadding(pad, pad, pad, pad + dp(parent, 4))
        }
        fun addButton(v: TextView) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(parent, 6); marginEnd = dp(parent, 6) }
            btnRow.addView(v, lp)
        }
        addButton(pill(parent, "Причесать", big = false) { onStopWith?.invoke(PravkaPrompts.REDO_POLISH) })
        addButton(pill(parent, "■  Стоп", big = true) { onStop?.invoke() })
        addButton(pill(parent, "Короче", big = false) { onStopWith?.invoke(PravkaPrompts.REDO_SHORTER) })

        val column = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF5241F19.toInt())  // near-opaque ink
            addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        textView = tv
        scroll = sc
        buttons = btnRow
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

    fun update(text: String) {
        val tv = textView ?: return
        tv.text = text
        // Teleprompter: keep the newest words visible.
        scroll?.post { scroll?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** The take ended, the fix is streaming: buttons are no longer applicable. */
    fun hideButtons() {
        buttons?.isVisible = false
    }

    fun hide() {
        val p = panel ?: return
        panel = null
        textView = null
        scroll = null
        buttons = null
        runCatching { (p.parent as? ViewGroup)?.removeView(p) }
    }

    val isShowing: Boolean get() = panel != null
}
