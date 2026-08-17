// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.pravka

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.keyboard.KeyboardSwitcher

// The dictation surface: while a take (or a streaming fix) is live, the key
// area is covered by a dark panel where the text appears - "the keyboard
// becomes the ticker". The toolbar/suggestion strip above stays visible and
// usable. Tapping the panel stops the take.
class PravkaOverlay {

    private var panel: ScrollView? = null
    private var textView: TextView? = null

    var onTap: (() -> Unit)? = null

    private fun host(): ViewGroup? =
        KeyboardSwitcher.getInstance().mainKeyboardView?.parent as? ViewGroup

    @SuppressLint("ClickableViewAccessibility")
    fun show(hint: String) {
        if (panel != null) { update(hint); return }
        val parent = host() ?: return
        val tv = TextView(parent.context).apply {
            setTextColor(0xFFF7F3EA.toInt())
            textSize = 18f
            setLineSpacing(0f, 1.15f)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            text = hint
        }
        val scroll = ScrollView(parent.context).apply {
            setBackgroundColor(0xF5241F19.toInt())  // near-opaque ink
            isFillViewport = true
            addView(
                tv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
            setOnClickListener { onTap?.invoke() }
        }
        textView = tv
        panel = scroll
        runCatching {
            parent.addView(
                scroll,
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
        panel?.post { panel?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    fun hide() {
        val p = panel ?: return
        panel = null
        textView = null
        runCatching { (p.parent as? ViewGroup)?.removeView(p) }
    }

    val isShowing: Boolean get() = panel != null
}
