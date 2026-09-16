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

/**
 * Свои файлы шрифтов прописи, положенные через настройки; null — встроенный шрифт. version меняется при любой
 * замене файла: по ней экраны понимают, что шрифт надо перечитать, не трогая диск при каждой перекомпозиции.
 */
data class CursiveFontFiles(val en: File? = null, val ru: File? = null, val version: Long = 0L) {
    fun forLang(lang: Lang): File? = if (lang == Lang.EN) en else ru
}

/**
 * Прописные начертания для Гармошки: как слово пишется в тетради, со всеми соединениями.
 * Английский — Playwrite US Trad (школьный курсив в духе Zaner-Bloser, лицензия OFL, текст в assets/licenses).
 * Русский — Propisi (ParaGraph, 1997): настоящая школьная пропись, вшита по решению Саши для домашнего
 * пользования; латиницы в файле нет, поэтому шрифт только для русских слов. Любой из двух можно заменить
 * своим файлом в настройках.
 */
object CursiveFonts {
    val En: FontFamily = FontFamily(Font(R.font.playwrite_us_trad))
    val Ru: FontFamily = FontFamily(Font(R.font.propisi))
    const val EN_NAME = "Playwrite US Trad"
    const val RU_NAME = "Propisi"

    fun bundled(lang: Lang): FontFamily = if (lang == Lang.EN) En else Ru
    fun bundledName(lang: Lang): String = if (lang == Lang.EN) EN_NAME else RU_NAME
}

/** Шрифт прописи для языка: свой файл, если он есть, иначе встроенный. Новый экземпляр Font на каждую версию: замена подхватывается сразу. */
@Composable
fun cursiveFamily(lang: Lang, fonts: CursiveFontFiles): FontFamily {
    val custom = fonts.forLang(lang)
    return remember(lang, custom?.path, fonts.version) {
        custom?.let { FontFamily(Font(it)) } ?: CursiveFonts.bundled(lang)
    }
}

/**
 * Слово прописью под печатным. Размер по длине, как у печатного, но меньше: у прописи высокие петли и хвосты.
 * У Propisi мелкие строчные буквы, поэтому русский идёт крупнее на пятую часть.
 */
@Composable
fun CursiveWord(text: String, lang: Lang, fonts: CursiveFontFiles, modifier: Modifier = Modifier) {
    val base = when {
        text.length <= 5 -> 56.sp
        text.length <= 8 -> 44.sp
        text.length <= 14 -> 34.sp
        text.length <= 24 -> 26.sp
        else -> 22.sp
    }
    val size = if (lang == Lang.RU) base * 1.2f else base
    Text(
        text,
        style = TextStyle(fontFamily = cursiveFamily(lang, fonts), fontSize = size, lineHeight = size * 1.6, color = PravkaColors.Ink),
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}
