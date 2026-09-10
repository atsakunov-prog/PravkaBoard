package ru.tsakunov.pravka.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Палитра (проверена валидатором dataviz: EN синий и RU оранжевый различимы при дальтонизме).
object PravkaColors {
    val Page = Color(0xFFF9F9F7)
    val Surface = Color(0xFFFCFCFB)
    val Surface2 = Color(0xFFF1F0EC)
    val Ink = Color(0xFF0B0B0B)
    val Ink2 = Color(0xFF52514E)
    val Muted = Color(0xFF898781)
    val Grid = Color(0xFFE1E0D9)
    val Axis = Color(0xFFC3C2B7)
    val Border = Color(0x1A0B0B0B)

    val En = Color(0xFF2A78D6)
    val EnSoft = Color(0xFFCDE2FB)
    val EnText = Color(0xFF1C5CAB)
    val Ru = Color(0xFFEB6834)
    val RuSoft = Color(0xFFFBD9CB)
    val RuText = Color(0xFFA63D16)

    val Good = Color(0xFF0CA30C)
    val GoodSoft = Color(0xFFDFF3DF)
    val GoodText = Color(0xFF006300)
    val Gold = Color(0xFFEDA100)
    val GoldSoft = Color(0xFFFFF1C9)
    val GoldText = Color(0xFF8A5A00)
    val Danger = Color(0xFFD03B3B)
    val Violet = Color(0xFF4A3AA7)
}

private val LightColors = lightColorScheme(
    primary = PravkaColors.Ink,
    onPrimary = Color.White,
    primaryContainer = PravkaColors.Surface2,
    onPrimaryContainer = PravkaColors.Ink,
    secondary = PravkaColors.En,
    onSecondary = Color.White,
    secondaryContainer = PravkaColors.EnSoft,
    onSecondaryContainer = PravkaColors.EnText,
    tertiary = PravkaColors.Ru,
    onTertiary = Color.White,
    tertiaryContainer = PravkaColors.RuSoft,
    onTertiaryContainer = PravkaColors.RuText,
    background = PravkaColors.Page,
    onBackground = PravkaColors.Ink,
    surface = PravkaColors.Surface,
    onSurface = PravkaColors.Ink,
    surfaceVariant = PravkaColors.Surface2,
    onSurfaceVariant = PravkaColors.Ink2,
    surfaceContainer = PravkaColors.Surface,
    surfaceContainerLow = PravkaColors.Surface,
    surfaceContainerHigh = PravkaColors.Surface2,
    surfaceContainerHighest = PravkaColors.Surface2,
    outline = PravkaColors.Axis,
    outlineVariant = PravkaColors.Grid,
    error = PravkaColors.Danger,
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

@Composable
fun PravkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = PravkaTypography,
        shapes = PravkaShapes,
        content = content,
    )
}
