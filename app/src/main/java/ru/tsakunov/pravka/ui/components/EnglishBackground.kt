package ru.tsakunov.pravka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Castle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.EmojiFoodBeverage
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.tsakunov.pravka.ui.theme.PravkaColors
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Фон всего приложения: тёплый белый лист, а по нему еле заметный английский узор —
 * прописные буквы, короткие слова и лондонские мелочи (чайная чашка, зонт, автобус, замок).
 * Всё серым на пять процентов: угадывается, но не мешает читать плашки поверх.
 */
@Composable
fun EnglishBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val measurer = rememberTextMeasurer()
    val icons = listOf(
        rememberVectorPainter(Icons.Filled.EmojiFoodBeverage),
        rememberVectorPainter(Icons.Filled.Umbrella),
        rememberVectorPainter(Icons.Filled.DirectionsBus),
        rememberVectorPainter(Icons.Filled.Castle),
        rememberVectorPainter(Icons.Filled.MenuBook),
        rememberVectorPainter(Icons.Filled.Cloud),
    )
    val ink = PravkaColors.Ink
    val letterStyle = TextStyle(fontFamily = FontFamily.Cursive, fontSize = 42.sp, color = ink.copy(alpha = 0.055f))
    val wordStyle = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 20.sp, color = ink.copy(alpha = 0.05f))
    val iconTint = ColorFilter.tint(ink)

    Box(
        modifier
            .fillMaxSize()
            .background(PravkaColors.PageBase)
            .drawBehind {
                val cell = 112.dp.toPx()
                val iconPx = 34.dp.toPx()
                val cols = ceil(size.width / cell).toInt() + 1
                val rows = ceil(size.height / cell).toInt() + 1
                // Один и тот же посев на каждой отрисовке: узор не «плывёт» при перекомпозиции.
                val rnd = Random(20260911)
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val x = c * cell + rnd.nextFloat() * cell * 0.55f - cell * 0.2f
                        val y = r * cell + rnd.nextFloat() * cell * 0.55f - cell * 0.2f
                        val angle = (rnd.nextFloat() - 0.5f) * 44f
                        val pick = rnd.nextInt(100)
                        rotate(angle, pivot = Offset(x, y)) {
                            when {
                                pick < 55 -> drawText(measurer, LETTERS[rnd.nextInt(LETTERS.size)], topLeft = Offset(x, y), style = letterStyle)
                                pick < 78 -> drawText(measurer, WORDS[rnd.nextInt(WORDS.size)], topLeft = Offset(x, y), style = wordStyle)
                                else -> {
                                    val painter = icons[rnd.nextInt(icons.size)]
                                    translate(x, y) { with(painter) { draw(Size(iconPx, iconPx), alpha = 0.06f, colorFilter = iconTint) } }
                                }
                            }
                        }
                    }
                }
            },
        content = content,
    )
}

private val LETTERS = listOf("Aa", "Bb", "Cc", "Dd", "Ee", "Ff", "Gg", "Hh", "Kk", "Ll", "Mm", "Nn", "Pp", "Rr", "Ss", "Tt", "Ww", "Yy")
private val WORDS = listOf("hello", "tea", "a hen", "book", "sun", "cat", "good morning", "London", "please", "the goose", "rain", "warm")
