package ru.tsakunov.pravka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.ui.theme.PravkaColors

fun Lang.color(): Color = if (this == Lang.EN) PravkaColors.En else PravkaColors.Ru
fun Lang.softColor(): Color = if (this == Lang.EN) PravkaColors.EnSoft else PravkaColors.RuSoft
fun Lang.textColor(): Color = if (this == Lang.EN) PravkaColors.EnText else PravkaColors.RuText
fun Lang.title(): String = if (this == Lang.EN) "English" else "Русский"

@Composable
fun LangTag(lang: Lang, modifier: Modifier = Modifier, big: Boolean = false) {
    Box(
        modifier
            .background(lang.softColor(), CircleShape)
            .padding(horizontal = if (big) 12.dp else 8.dp, vertical = if (big) 4.dp else 2.dp),
    ) {
        Text(
            if (lang == Lang.EN) "EN" else "RU",
            color = lang.textColor(),
            fontWeight = FontWeight.Bold,
            fontSize = if (big) 14.sp else 11.sp,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(bg, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun PravkaCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = PravkaColors.Surface,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, PravkaColors.Border),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 6.dp, bottom = 6.dp))
}

@Composable
fun BackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
    }
}

enum class Tab(val label: String) {
    PROPISI("Гармошка"), WORDS("Слова"), GRAMMAR("Грамматика"), HOMEWORK("Домашка"), TEXT("Текст"), STATS("Статистика")
}

/**
 * Шесть вкладок: на узком экране подписи показываются только у выбранной (иначе «Грамматика» и «Статистика»
 * не помещаются), на широком (раскрытый складной телефон, планшет) — у всех.
 */
@Composable
fun PravkaBottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 480
    NavigationBar(containerColor = PravkaColors.Surface, tonalElevation = 0.dp) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        when (tab) {
                            Tab.PROPISI -> Icons.Filled.Edit
                            Tab.WORDS -> Icons.Filled.Style
                            Tab.GRAMMAR -> Icons.Filled.School
                            Tab.HOMEWORK -> Icons.Filled.AssignmentTurnedIn
                            Tab.TEXT -> Icons.Filled.MenuBook
                            Tab.STATS -> Icons.Filled.BarChart
                        },
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) },
                alwaysShowLabel = wide,
                colors = navColors(),
            )
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = PravkaColors.Ink,
    selectedTextColor = PravkaColors.Ink,
    indicatorColor = PravkaColors.Surface2,
    unselectedIconColor = PravkaColors.Muted,
    unselectedTextColor = PravkaColors.Muted,
)

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = PravkaColors.Ink,
    content: Color = Color.White,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PravkaColors.Ink),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = PravkaColors.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Кнопка «вся домашка» в шапке вкладок: разбор пакета фото по разделам. */
@Composable
fun IntakeIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(androidx.compose.material.icons.Icons.Filled.DocumentScanner, contentDescription = "Вся домашка", tint = PravkaColors.Ink)
    }
}
