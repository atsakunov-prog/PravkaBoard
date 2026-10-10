// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils

/**
 * PravkaBoard: key material of the Pravka app, the same convex key as its floating buttons
 * (ru.zf.pravka.trigger.BubbleSkin): light from above, a sheen at the top edge, shade at the bottom,
 * a thin rim that is light on top and dark below, and a side wall under the face. A pressed key goes
 * down onto its side wall and the light slides off it.
 */
enum class KeyMaterial(
    /** color of the light: the sheen at the top edge and the light part of the rim */
    val lightColor: Int,
    /** sheen at the top edge, alpha of [lightColor] */
    val sheen: Float,
    val rimLight: Float,
    /** color of the shade at the bottom, of the dark part of the rim and of the side wall */
    val shadeColor: Int,
    /** shade at the bottom edge, alpha of [shadeColor] */
    val foot: Float,
    val rimShade: Float,
    /** how much darker than the face the side wall is */
    val side: Float,
) {
    /** neutral warm glass of the Pravka "Today" screen, the light is cream and not white */
    GLASS(Color.rgb(255, 230, 200), 0.13f, 0.30f, Color.BLACK, 0.12f, 0.16f, 0.5f),
    /** glass of the Pravka mode, lit in its tint */
    GLASS_INK(Color.rgb(245, 146, 90), 0.13f, 0.36f, Color.BLACK, 0.12f, 0.16f, 0.5f),
    /** CreamKey of the Pravka app: a bright sheen, a brown shade instead of a black one */
    CREAM(Color.WHITE, 0.75f, 0.60f, Color.rgb(120, 90, 50), 0.10f, 0.16f, 0.28f),
    /** the floating Pravka buttons themselves, for the action key */
    KEY(Color.WHITE, 0.26f, 0.34f, Color.BLACK, 0.10f, 0.12f, 0.4f);

    /** material of shift, delete and the other functional keys */
    val functional get() = if (this == GLASS) GLASS_INK else this
}

class KeySkin(
    res: Resources,
    radiusDp: Float,
    private val material: KeyMaterial,
) : Drawable() {
    private val density = res.displayMetrics.density
    private val radius = radiusDp * density
    private val depth = DEPTH_DP * density
    private val rimWidth = RIM_DP * density

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = rimWidth }
    private val rect = RectF()

    private var tint: ColorStateList? = null
    private var color = Color.WHITE
    private var pressed = false
    private var builtHeight = -1f
    private var skinAlpha = 255

    init {
        updateColor()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val faceHeight = b.height() - depth
        if (w <= 0f || faceHeight <= 0f) return
        if (faceHeight != builtHeight) build(faceHeight)
        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        if (pressed) {
            canvas.translate(0f, depth)
        } else {
            rect.set(0f, depth, w, faceHeight + depth)
            canvas.drawRoundRect(rect, radius, radius, sidePaint)
        }
        rect.set(0f, 0f, w, faceHeight)
        canvas.drawRoundRect(rect, radius, radius, facePaint)
        canvas.drawRoundRect(rect, radius, radius, foot)
        canvas.drawRoundRect(rect, radius, radius, sheen)
        val inset = rimWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, radius - inset, radius - inset, rim)
        canvas.restoreToCount(save)
    }

    // the drawable is shared by all keys and only rebuilt when the key height changes, which is rare
    private fun build(h: Float) {
        builtHeight = h
        sheen.shader = LinearGradient(0f, 0f, 0f, h * SHEEN_SPAN,
            intArrayOf(light(material.sheen), light(material.sheen * 0.3f), light(0f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        foot.shader = LinearGradient(0f, h, 0f, h * (1 - FOOT_SPAN),
            intArrayOf(shade(material.foot), shade(0f)), null, Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(light(material.rimLight), light(0f), shade(material.rimShade)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        applyLight()
    }

    private fun applyLight() {
        val light = if (pressed) (skinAlpha * PRESSED_LIGHT).toInt() else skinAlpha
        sheen.alpha = light
        rim.alpha = light
        foot.alpha = skinAlpha
    }

    private fun updateColor() {
        facePaint.color = color
        sidePaint.color = ColorUtils.blendARGB(color, material.shadeColor, material.side)
        facePaint.alpha = Color.alpha(color) * skinAlpha / 255
        sidePaint.alpha = facePaint.alpha
    }

    private fun light(a: Float) = ColorUtils.setAlphaComponent(material.lightColor, (a * 255).toInt())
    private fun shade(a: Float) = ColorUtils.setAlphaComponent(material.shadeColor, (a * 255).toInt())

    // the colors come as tint like for the xml drawables: they are white, so a multiplied tint is just the color
    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        onStateChange(state)
        invalidateSelf()
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {}

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val newPressed = android.R.attr.state_pressed in state
        val newColor = tint?.getColorForState(state, tint!!.defaultColor) ?: Color.WHITE
        if (newPressed == pressed && newColor == color) return false
        pressed = newPressed
        color = newColor
        updateColor()
        applyLight()
        return true
    }

    override fun setAlpha(alpha: Int) {
        skinAlpha = alpha
        updateColor()
        applyLight()
        invalidateSelf()
    }
    override fun getAlpha() = skinAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        facePaint.colorFilter = colorFilter
        sidePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val DEPTH_DP = 1.5f
        private const val SHEEN_SPAN = 0.34f
        private const val FOOT_SPAN = 0.2f
        private const val RIM_DP = 1.2f
        private const val PRESSED_LIGHT = 0.3f
        const val KEY_RADIUS_DP = 10f
        const val ACTION_KEY_RADIUS_DP = 16f

        /** Same states as btn_keyboard_key_rounded_base_border: action keys, keys without background
         *  that only show when pressed, and normal keys. Pressed is handled by the skin itself. */
        fun stateList(res: Resources, material: KeyMaterial): Drawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_active), KeySkin(res, ACTION_KEY_RADIUS_DP, KeyMaterial.KEY))
            addState(intArrayOf(android.R.attr.state_empty, android.R.attr.state_pressed), KeySkin(res, KEY_RADIUS_DP, material))
            addState(intArrayOf(android.R.attr.state_empty), ColorDrawable(Color.TRANSPARENT))
            addState(intArrayOf(), KeySkin(res, KEY_RADIUS_DP, material))
        }
    }
}

/**
 * PravkaBoard: the light at the top of the Pravka screens (the "Today" light: cream on the left, warm amber
 * on the right), much fainter, since the keyboard is at the bottom where the Pravka screens are already dark.
 * Used as keyboard background image, so it's drawn once into a bitmap.
 */
class PravkaLight(private val base: Int, private val strength: Float) : Drawable() {
    private class Blob(val cx: Float, val rx: Float, val ry: Float, val color: Int, val alpha: Float)
    private val blobs = listOf(
        Blob(0.20f, 0.64f, 0.75f, Color.rgb(255, 214, 160), 0.40f),
        Blob(0.90f, 0.50f, 0.65f, Color.rgb(247, 136, 16), 0.30f),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.drawColor(base)
        if (w <= 0f || h <= 0f) return
        for (blob in blobs) {
            val rx = w * blob.rx
            val ry = h * blob.ry
            val color = ColorUtils.setAlphaComponent(blob.color, (blob.alpha * strength * 255).toInt())
            paint.shader = RadialGradient(0f, 0f, rx, intArrayOf(color, ColorUtils.setAlphaComponent(blob.color, 0)),
                floatArrayOf(0f, 0.72f), Shader.TileMode.CLAMP)
            val save = canvas.save()
            canvas.translate(b.left + w * blob.cx, b.top.toFloat())
            canvas.scale(1f, ry / rx)
            canvas.drawCircle(0f, 0f, rx, paint)
            canvas.restoreToCount(save)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
