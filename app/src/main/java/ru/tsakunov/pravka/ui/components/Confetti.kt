package ru.tsakunov.pravka.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import ru.tsakunov.pravka.ui.theme.PravkaColors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class Particle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    val w: Float, val h: Float,
    val color: Color,
    var angle: Float, val spin: Float,
    val drag: Float,
)

/**
 * Конфетти. Каждый раз, когда [trigger] меняется (и не равен 0), выстреливает залп.
 * Рисуется поверх содержимого, касания не перехватывает.
 */
@Composable
fun ConfettiOverlay(trigger: Int, modifier: Modifier = Modifier, big: Boolean = true) {
    val particles = remember { mutableStateOf<List<Particle>>(emptyList()) }
    var frame by remember { mutableLongStateOf(0L) }
    val colors = remember {
        listOf(PravkaColors.Gold, PravkaColors.En, PravkaColors.Ru, PravkaColors.Good, PravkaColors.Violet, Color(0xFFE87BA4))
    }

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        val rnd = Random(trigger)
        val count = if (big) 140 else 60
        val list = ArrayList<Particle>(count)
        // Два источника по бокам сверху плюс центральный залп.
        repeat(count) { i ->
            val side = i % 3
            val (sx, sy) = when (side) { 0 -> 0.15f to 0.35f; 1 -> 0.85f to 0.35f; else -> 0.5f to 0.55f }
            val angle = when (side) { 0 -> -70f + rnd.nextFloat() * 50f; 1 -> -160f + rnd.nextFloat() * 50f; else -> -130f + rnd.nextFloat() * 80f }
            val speed = (if (big) 18f else 12f) + rnd.nextFloat() * 14f
            val rad = Math.toRadians(angle.toDouble())
            list += Particle(
                x = sx, y = sy,
                vx = (cos(rad) * speed).toFloat(), vy = (sin(rad) * speed).toFloat(),
                w = 8f + rnd.nextFloat() * 8f, h = 5f + rnd.nextFloat() * 6f,
                color = colors[rnd.nextInt(colors.size)],
                angle = rnd.nextFloat() * 360f, spin = -8f + rnd.nextFloat() * 16f,
                drag = 0.965f + rnd.nextFloat() * 0.02f,
            )
        }
        particles.value = list
        val start = withFrameNanos { it }
        var last = start
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000f).coerceAtMost(40f) / 16.7f
            last = now
            val elapsed = (now - start) / 1_000_000_000f
            for (p in list) {
                p.vy += 0.9f * dt
                p.vx *= p.drag
                p.vy *= p.drag
                p.x += p.vx * dt / 1000f
                p.y += p.vy * dt / 1000f
                p.angle += p.spin * dt
            }
            frame = now
            if (elapsed > 3.2f) break
        }
        particles.value = emptyList()
        frame = 0L
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame
        val list = particles.value
        if (list.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        for (p in list) {
            val px = p.x * w
            val py = p.y * h
            if (py > h + 20) continue
            rotate(p.angle, pivot = Offset(px, py)) {
                drawRect(color = p.color, topLeft = Offset(px - p.w / 2, py - p.h / 2), size = Size(p.w, p.h))
            }
        }
    }
}
