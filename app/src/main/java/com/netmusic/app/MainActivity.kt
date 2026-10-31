package com.netmusic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF080808)
private val Panel = Color(0xFF191919)
private val Muted = Color(0xFF929292)
private val Accent = Color(0xFFE9D9B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                NetMusicApp()
            }
        }
    }
}

private enum class Tab(val title: String) {
    HOME("Главная"), LIBRARY("Медиатека"), SEARCH("Поиск"), SETTINGS("Настройки")
}

@Composable
private fun NetMusicApp() {
    var selectedTab by remember { mutableStateOf(Tab.HOME) }
    var isPlaying by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }

    Surface(color = Background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.navigationBars.asPaddingValues())
        ) {
            AnimatedContent(
                targetState = if (showPlayer) "player" else selectedTab.name,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen"
            ) { screen ->
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (screen == "player") {
                        PlayerScreen(isPlaying, { isPlaying = !isPlaying }, { showPlayer = false })
                    } else {
                        when (Tab.valueOf(screen)) {
                            Tab.HOME -> HomeScreen(onOpenPlayer = { showPlayer = true })
                            Tab.LIBRARY -> LibraryScreen()
                            Tab.SEARCH -> SearchScreen()
                            Tab.SETTINGS -> SettingsScreen()
                        }
                    }
                }
            }

            if (!showPlayer) {
                if (selectedTab != Tab.SETTINGS) {
                    MiniPlayer(isPlaying, { isPlaying = !isPlaying }, { showPlayer = true })
                }
                BottomBar(selectedTab) { selectedTab = it }
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpenPlayer: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("NET.", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    Text("Моя волна", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text("◉", color = Color.White, fontSize = 26.sp)
            }
        }
        item {
            Box(
                Modifier.fillMaxWidth().height(310.dp).clip(RoundedCornerShape(30.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF51352F), Color(0xFF171717), Color(0xFF080808))))
                    .clickable { onOpenPlayer() }.padding(24.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("YOUR SOUND. YOUR SPACE.", color = Accent, fontSize = 10.sp, letterSpacing = 2.sp)
                    Text("Музыка
без границ", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
                    Text("Открой плеер  →", color = Color.White.copy(alpha = .75f), fontSize = 14.sp)
                }
            }
        }
        item { SectionTitle("Твоя музыка") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CollectionCard("Любимые", "Треки, которые нравятся", Modifier.weight(1f), Color(0xFF7D2024))
                CollectionCard("Недавние", "История прослушивания", Modifier.weight(1f), Color(0xFF333333))
            }
        }
        item { SectionTitle("Подключённые сервисы") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ServiceRow("Spotify", "Подключение будет доступно позже", "●", Color(0xFF1DB954))
                ServiceRow("SoundCloud", "Подключение будет доступно позже", "◉", Color(0xFFFF6A2B))
                ServiceRow("YouTube Music", "В разработке", "▶", Color(0xFFFF4545))
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun LibraryScreen() {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Медиатека", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Всё, что ты сохраняешь, будет здесь.", color = Muted)
        CollectionCard("Любимые треки", "Твоя личная коллекция", Modifier.fillMaxWidth(), Color(0xFF7D2024))
        CollectionCard("Плейлисты", "Создавай свои подборки", Modifier.fillMaxWidth(), Panel)
        CollectionCard("Недавно прослушанное", "История воспроизведения", Modifier.fillMaxWidth(), Panel)
        CollectionCard("Загрузки", "Музыка офлайн", Modifier.fillMaxWidth(), Panel)
    }
}

@Composable
private fun SearchScreen() {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Поиск", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = Muted)
            Text("Треки, артисты, альбомы", color = Muted, modifier = Modifier.padding(start = 12.dp))
        }
        Text("Ищи музыку сразу в нескольких сервисах.", color = Muted)
        Text("Глобальный поиск появится после подключения API.", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Настройки", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        listOf("Подключённые сервисы", "Качество звука", "Оформление", "Хранилище", "О приложении").forEach {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(it, color = Color.White, fontSize = 16.sp)
                Text("›", color = Muted, fontSize = 22.sp)
            }
        }
        Text("Net.music · версия 0.1.0", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PlayerScreen(isPlaying: Boolean, onToggle: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("⌄", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable { onClose() })
            Text("СЕЙЧАС ИГРАЕТ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Text("•••", color = Color.White)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF6A343B), Color(0xFF24202A), Color(0xFF101010)))),
                contentAlignment = Alignment.Center
            ) {
                Text("NET", color = Color.White.copy(alpha = .85f), fontSize = 64.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            }
            Text("Выбери свой звук", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Net.music · демо-плеер", color = Muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0:00", color = Muted, fontSize = 12.sp)
                Text("0:00", color = Muted, fontSize = 12.sp)
            }
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Panel))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp))
                Box(Modifier.size(70.dp).clip(CircleShape).background(Accent).clickable { onToggle() }, contentAlignment = Alignment.Center) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(38.dp))
                }
                Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Text("Демо-экран. Реальное воспроизведение подключим следующим этапом.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MiniPlayer(isPlaying: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp)).background(Color(0xFF202020))
            .clickable { onOpen() }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFF8D3544), Color(0xFF302536)))), contentAlignment = Alignment.Center) {
            Text("N", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Выбери свой звук", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Net.music", color = Muted, fontSize = 11.sp)
        }
        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.clickable { onToggle() }.size(30.dp))
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF101010)).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tab.entries.forEach { tab ->
            val icon = when (tab) {
                Tab.HOME -> Icons.Default.Home
                Tab.LIBRARY -> Icons.Default.Folder
                Tab.SEARCH -> Icons.Default.Search
                Tab.SETTINGS -> Icons.Default.Settings
            }
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).clickable { onSelect(tab) }.padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = tab.title, tint = if (selected == tab) Color.White else Muted, modifier = Modifier.size(23.dp))
                Text(tab.title, color = if (selected == tab) Color.White else Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun CollectionCard(title: String, subtitle: String, modifier: Modifier, color: Color) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(color).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Favorite, null, tint = Color.White.copy(alpha = .9f), modifier = Modifier.size(22.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(subtitle, color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
    }
}

@Composable
private fun ServiceRow(name: String, status: String, mark: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(color.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Text(mark, color = color, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(status, color = Muted, fontSize = 11.sp)
        }
    }
}
