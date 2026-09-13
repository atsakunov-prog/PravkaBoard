package ru.tsakunov.pravka.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Набор цветов приложения. Два экземпляра: тёплый для телефона и контрастный для электронной книги.
 * На цветном E-Ink (Kaleido) краски бледнеют вдвое, светло-серый текст пропадает, полупрозрачные рамки
 * не видны, а тени превращаются в грязь. Поэтому в палитре ридера: чистый белый лист, чёрный текст даже
 * для второстепенного, насыщенные и более тёмные акценты, плотные подложки вместо пастельных и сплошные рамки.
 */
class Palette(
    val reader: Boolean,
    val pageBase: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val grid: Color,
    val axis: Color,
    val border: Color,
    val en: Color,
    val enSoft: Color,
    val enText: Color,
    val ru: Color,
    val ruSoft: Color,
    val ruText: Color,
    val good: Color,
    val goodSoft: Color,
    val goodText: Color,
    val gold: Color,
    val goldSoft: Color,
    val goldText: Color,
    val danger: Color,
    val violet: Color,
)

object Palettes {
    // Палитра телефона (проверена валидатором dataviz: EN синий и RU оранжевый различимы при дальтонизме).
    val Warm = Palette(
        reader = false,
        pageBase = Color(0xFFF9F9F7),
        surface = Color(0xFFFCFCFB),
        surface2 = Color(0xFFF1F0EC),
        ink = Color(0xFF0B0B0B),
        ink2 = Color(0xFF52514E),
        muted = Color(0xFF898781),
        grid = Color(0xFFE1E0D9),
        axis = Color(0xFFC3C2B7),
        border = Color(0x1A0B0B0B),
        en = Color(0xFF2A78D6),
        enSoft = Color(0xFFCDE2FB),
        enText = Color(0xFF1C5CAB),
        ru = Color(0xFFEB6834),
        ruSoft = Color(0xFFFBD9CB),
        ruText = Color(0xFFA63D16),
        good = Color(0xFF0CA30C),
        goodSoft = Color(0xFFDFF3DF),
        goodText = Color(0xFF006300),
        gold = Color(0xFFEDA100),
        goldSoft = Color(0xFFFFF1C9),
        goldText = Color(0xFF8A5A00),
        danger = Color(0xFFD03B3B),
        violet = Color(0xFF4A3AA7),
    )

    // Палитра ридера: белый лист, чёрный текст, яркие акценты, сплошные рамки.
    val Ink = Palette(
        reader = true,
        pageBase = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surface2 = Color(0xFFE4E4E4),
        ink = Color(0xFF000000),
        ink2 = Color(0xFF1A1A1A),
        muted = Color(0xFF3A3A3A),
        grid = Color(0xFF9C9C9C),
        axis = Color(0xFF000000),
        border = Color(0xFF000000),
        en = Color(0xFF0047CC),
        enSoft = Color(0xFFB3CCFF),
        enText = Color(0xFF002699),
        ru = Color(0xFFE64A00),
        ruSoft = Color(0xFFFFC2A3),
        ruText = Color(0xFF8A2A00),
        good = Color(0xFF008A00),
        goodSoft = Color(0xFFB3E6B3),
        goodText = Color(0xFF005200),
        gold = Color(0xFFD98C00),
        goldSoft = Color(0xFFFFE080),
        goldText = Color(0xFF664200),
        danger = Color(0xFFCC0000),
        violet = Color(0xFF3D1FA3),
    )
}

/**
 * Цвета, которыми пользуются экраны. Текущая палитра лежит в снимке Compose: любой composable, читающий
 * PravkaColors.En, перерисуется при переключении режима ридера без перезапуска. Экраны продолжают писать
 * PravkaColors.X, как раньше.
 */
object PravkaColors {
    var palette: Palette by mutableStateOf(Palettes.Warm)

    /** Режим ридера включён: без конфетти, плавных переходов и теней. */
    val reader: Boolean get() = palette.reader

    /** Экраны и шапки прозрачные: фон рисует EnglishBackground в корне. */
    val Page = Color.Transparent
    /** Настоящий цвет страницы под узором. */
    val PageBase: Color get() = palette.pageBase
    val Surface: Color get() = palette.surface
    val Surface2: Color get() = palette.surface2
    val Ink: Color get() = palette.ink
    val Ink2: Color get() = palette.ink2
    val Muted: Color get() = palette.muted
    val Grid: Color get() = palette.grid
    val Axis: Color get() = palette.axis
    val Border: Color get() = palette.border

    val En: Color get() = palette.en
    val EnSoft: Color get() = palette.enSoft
    val EnText: Color get() = palette.enText
    val Ru: Color get() = palette.ru
    val RuSoft: Color get() = palette.ruSoft
    val RuText: Color get() = palette.ruText

    val Good: Color get() = palette.good
    val GoodSoft: Color get() = palette.goodSoft
    val GoodText: Color get() = palette.goodText
    val Gold: Color get() = palette.gold
    val GoldSoft: Color get() = palette.goldSoft
    val GoldText: Color get() = palette.goldText
    val Danger: Color get() = palette.danger
    val Violet: Color get() = palette.violet

    fun paletteFor(reader: Boolean): Palette = if (reader) Palettes.Ink else Palettes.Warm
}

/** Анимация цвета: на E-Ink каждый промежуточный кадр оставляет след, поэтому в режиме ридера цвет меняется сразу. */
fun <T> motion(): AnimationSpec<T> = if (PravkaColors.reader) snap() else spring()

private fun colorSchemeFor(p: Palette) = lightColorScheme(
    primary = p.ink,
    onPrimary = Color.White,
    primaryContainer = p.surface2,
    onPrimaryContainer = p.ink,
    secondary = p.en,
    onSecondary = Color.White,
    secondaryContainer = p.enSoft,
    onSecondaryContainer = p.enText,
    tertiary = p.ru,
    onTertiary = Color.White,
    tertiaryContainer = p.ruSoft,
    onTertiaryContainer = p.ruText,
    background = p.pageBase,
    onBackground = p.ink,
    surface = p.surface,
    onSurface = p.ink,
    surfaceVariant = p.surface2,
    onSurfaceVariant = p.ink2,
    surfaceContainer = p.surface,
    surfaceContainerLow = p.surface,
    surfaceContainerHigh = p.surface2,
    surfaceContainerHighest = p.surface2,
    outline = p.axis,
    outlineVariant = p.grid,
    error = p.danger,
    onError = Color.White,
)

private val PravkaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val PravkaTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

// Ридер: тонкие штрихи на E-Ink рассыпаются, поэтому основной текст полужирный, мелкие подписи чуть крупнее.
private val ReaderTypography = PravkaTypography.copy(
    bodyLarge = PravkaTypography.bodyLarge.copy(fontWeight = FontWeight.Medium),
    bodyMedium = PravkaTypography.bodyMedium.copy(fontWeight = FontWeight.Medium),
    bodySmall = PravkaTypography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp),
    labelSmall = PravkaTypography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 15.sp),
    labelMedium = PravkaTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
)

@Composable
fun PravkaTheme(reader: Boolean = false, content: @Composable () -> Unit) {
    val palette = PravkaColors.paletteFor(reader)
    // Палитра выставляется и в MainActivity до первого кадра; здесь подхватывается переключение в настройках.
    SideEffect { if (PravkaColors.palette !== palette) PravkaColors.palette = palette }
    val scheme = remember(palette) { colorSchemeFor(palette) }
    MaterialTheme(
        colorScheme = scheme,
        typography = if (reader) ReaderTypography else PravkaTypography,
        shapes = PravkaShapes,
        content = content,
    )
}
