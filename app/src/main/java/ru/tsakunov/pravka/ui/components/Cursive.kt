package ru.tsakunov.pravka.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import ru.tsakunov.pravka.R
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.ui.theme.PravkaColors
import java.io.File

/** Свои файлы шрифтов прописи, положенные через настройки; null — встроенный шрифт. */
data class CursiveFontFiles(val en: File? = null, val ru: File? = null) {
    fun forLang(lang: Lang): File? = if (lang == Lang.EN) en else ru
}

/**
 * Прописные начертания для Гармошки: как слово пишется в тетради, со всеми соединениями.
 * Английский — Playwrite US Trad (школьный курсив в духе Zaner-Bloser, лицензия OFL, текст в assets/licenses).
 * Для русского школьной прописи со свободной лицензией не нашлось; встроен Marck Script (OFL) — ближайший
 * связный рукописный, а точную пропись (например, Propisi от ParaType) можно подложить файлом в настройках.
 */
object CursiveFonts {
    val En: FontFamily = FontFamily(Font(R.font.playwrite_us_trad))
    val Ru: FontFamily = FontFamily(Font(R.font.marck_script))
    const val EN_NAME = "Playwrite US Trad"
    const val RU_NAME = "Marck Script"

    fun bundled(lang: Lang): FontFamily = if (lang == Lang.EN) En else Ru
    fun bundledName(lang: Lang): String = if (lang == Lang.EN) EN_NAME else RU_NAME
}

/** Шрифт прописи для языка: свой файл, если он есть, иначе встроенный. Ключ по времени файла: замена подхватывается сразу. */
@Composable
fun cursiveFamily(lang: Lang, custom: File?): FontFamily {
    val path = custom?.path
    val stamp = custom?.lastModified() ?: 0L
    return remember(lang, path, stamp) {
        custom?.takeIf { it.isFile }?.let { FontFamily(Font(it)) } ?: CursiveFonts.bundled(lang)
    }
}

/** Слово прописью под печатным. Размер по длине, как у печатного, но меньше: у прописи высокие петли и хвосты. */
@Composable
fun CursiveWord(text: String, lang: Lang, custom: File?, modifier: Modifier = Modifier) {
    val size = when {
        text.length <= 5 -> 56.sp
        text.length <= 8 -> 44.sp
        text.length <= 14 -> 34.sp
        text.length <= 24 -> 26.sp
        else -> 22.sp
    }
    Text(
        text,
        style = TextStyle(fontFamily = cursiveFamily(lang, custom), fontSize = size, lineHeight = size * 1.6, color = PravkaColors.Ink),
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}
