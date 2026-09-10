@file:OptIn(ExperimentalMaterial3Api::class)

package ru.tsakunov.pravka.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.tsakunov.pravka.ui.components.PravkaBottomBar
import ru.tsakunov.pravka.ui.components.PravkaCard
import ru.tsakunov.pravka.ui.components.Tab
import ru.tsakunov.pravka.ui.theme.PravkaColors

/** Вкладка, которая появится в следующей сборке. */
@Composable
fun PlaceholderScreen(tab: Tab, description: String, onTab: (Tab) -> Unit) {
    Scaffold(
        containerColor = PravkaColors.Page,
        topBar = {
            TopAppBar(
                title = { Text(tab.label, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PravkaColors.Page),
            )
        },
        bottomBar = { PravkaBottomBar(tab, onTab) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            PravkaCard {
                Text("Скоро", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(description, color = PravkaColors.Ink2)
                Spacer(Modifier.height(6.dp))
                Text("Появится в одном из следующих обновлений, приложение само предложит его поставить.", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted)
            }
        }
    }
}
