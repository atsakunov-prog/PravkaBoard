package ru.tsakunov.pravka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

enum class Tab { WORDS, PROGRESS }

@Composable
fun PravkaBottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = PravkaColors.Surface, tonalElevation = 0.dp) {
        NavigationBarItem(
            selected = current == Tab.WORDS,
            onClick = { onSelect(Tab.WORDS) },
            icon = { Icon(Icons.Filled.List, contentDescription = null) },
            label = { Text("Слова") },
            colors = navColors(),
        )
        NavigationBarItem(
            selected = current == Tab.PROGRESS,
            onClick = { onSelect(Tab.PROGRESS) },
            icon = { Icon(Icons.Filled.ShowChart, contentDescription = null) },
            label = { Text("Прогресс") },
            colors = navColors(),
        )
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
