package com.netmusic.app

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private val Background = Color(0xFF080808)
private val Panel = Color(0xFF191919)
private val Muted = Color(0xFF929292)
private val Accent = Color(0xFFE9D9B8)
private val ioExecutor = Executors.newSingleThreadExecutor()

data class AudioTrack(val id: Long, val title: String, val artist: String, val album: String, val albumId: Long, val uri: Uri, val duration: Long)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController by mutableStateOf<MediaController?>(null)

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try { mediaController = future.get() } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        mediaController = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { NetMusicApp(mediaController) } }
    }
}

private enum class Tab(val title: String) {
    HOME("Главная"), LIBRARY("Медиатека"), SEARCH("Поиск"), SETTINGS("Настройки")
}

@Composable
private fun NetMusicApp(player: Player?) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(Tab.HOME) }
    var showPlayer by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var permissionGranted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("Выбери свой звук") }
    var currentArtist by remember { mutableStateOf("Локальная медиатека") }
    val preferences = remember { context.getSharedPreferences("net_music_preferences", android.content.Context.MODE_PRIVATE) }
    var favoriteIds by remember { mutableStateOf(preferences.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var recentIds by remember { mutableStateOf(preferences.getString("recent_tracks", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()) }
    fun toggleFavorite(track: AudioTrack) {
        favoriteIds = if (track.id.toString() in favoriteIds) favoriteIds - track.id.toString() else favoriteIds + track.id.toString()
        preferences.edit().putStringSet("favorites", favoriteIds).apply()
    }

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) loading = true
    }

    fun scanLibrary() {
        loading = true
        ioExecutor.execute {
            val found = mutableListOf<AudioTrack>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ALBUM_ID
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            try {
                context.contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val rawTitle = cursor.getString(titleCol).orEmpty()
                        val fileName = cursor.getString(displayNameCol).orEmpty().substringBeforeLast(".", cursor.getString(displayNameCol).orEmpty())
                        val title = cleanMediaText(rawTitle).ifBlank { cleanMediaText(fileName).ifBlank { "Без названия" } }
                        val artist = cleanMediaText(cursor.getString(artistCol).orEmpty()).takeUnless { it.equals("<unknown>", true) || it.isBlank() } ?: "Неизвестный исполнитель"
                        val album = cleanMediaText(cursor.getString(albumCol).orEmpty())
                        val duration = cursor.getLong(durationCol)
                        val albumId = cursor.getLong(albumIdCol)
                        found += AudioTrack(id, title, artist, album, albumId, ContentUris.withAppendedId(collection, id), duration)
                    }
                }
            } catch (_: SecurityException) { }
            Handler(Looper.getMainLooper()).post { tracks = found; loading = false }
        }
    }

    LaunchedEffect(Unit) {
        permissionGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = player?.currentMediaItemIndex ?: -1
                currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: "Выбери свой звук"
                currentArtist = mediaItem?.mediaMetadata?.artist?.toString() ?: "Локальная медиатека"
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    LaunchedEffect(permissionGranted) { if (permissionGranted) scanLibrary() }

    fun playTrack(index: Int) {
        val p = player ?: return
        val items = tracks.map { track ->
            MediaItem.Builder().setUri(track.uri).setMediaMetadata(
                MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build()
            ).build()
        }
        p.setMediaItems(items, index, 0L)
        recentIds = (listOf(tracks[index].id.toString()) + recentIds.filterNot { it == tracks[index].id.toString() }).take(30)
        preferences.edit().putString("recent_tracks", recentIds.joinToString(",")).apply()
        p.prepare()
        p.play()
        currentIndex = index
        currentTitle = tracks[index].title
        currentArtist = tracks[index].artist
        showPlayer = true
    }

    Surface(color = Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(WindowInsets.navigationBars.asPaddingValues())) {
            AnimatedContent(
                targetState = if (showPlayer) "player" else selectedTab.name,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen"
            ) { screen ->
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (screen == "player") {
                        PlayerScreen(player, currentTitle, currentArtist, isPlaying, { showPlayer = false })
                    } else when (Tab.valueOf(screen)) {
                        Tab.HOME -> HomeScreen(tracks.size, favoriteIds.size, recentIds.mapNotNull { id -> tracks.find { it.id.toString() == id } }, loading, permissionGranted, onOpenLibrary = { selectedTab = Tab.LIBRARY }, onRefresh = { scanLibrary() }, onPlayRecent = { track -> playTrack(tracks.indexOfFirst { it.id == track.id }) })
                        Tab.LIBRARY -> LibraryScreen(tracks, loading, permissionGranted, favoriteIds,
                            onRequestPermission = { requestPermission.launch(permission) },
                            onRefresh = { scanLibrary() }, onPlay = { playTrack(it) }, onToggleFavorite = { toggleFavorite(it) })
                        Tab.SEARCH -> SearchScreen(tracks, favoriteIds, onPlay = { playTrack(it) }, onToggleFavorite = { toggleFavorite(it) })
                        Tab.SETTINGS -> SettingsScreen()
                    }
                }
            }
            if (!showPlayer) {
                if (selectedTab != Tab.SETTINGS) {
                    MiniPlayer(currentTitle, currentArtist, isPlaying, { player?.let { if (it.isPlaying) it.pause() else it.play() } }, { showPlayer = true })
                }
                BottomBar(selectedTab) { selectedTab = it }
            }
        }
    }
}

@Composable
private fun HomeScreen(trackCount: Int, favoriteCount: Int, recentTracks: List<AudioTrack>, loading: Boolean, permissionGranted: Boolean, onOpenLibrary: () -> Unit, onRefresh: () -> Unit, onPlayRecent: (AudioTrack) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Column(Modifier.padding(top = 28.dp)) {
                Text("NET.", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("Моя волна", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        item {
            Box(Modifier.fillMaxWidth().height(270.dp).clip(RoundedCornerShape(30.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF51352F), Color(0xFF171717), Color(0xFF080808))))
                .clickable { onOpenLibrary() }.padding(24.dp), contentAlignment = Alignment.BottomStart) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("YOUR SOUND. YOUR SPACE.", color = Accent, fontSize = 10.sp, letterSpacing = 2.sp)
                    Text("Музыка\nбез границ", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
                    Text("Открыть медиатеку  →", color = Color.White.copy(alpha = .75f), fontSize = 14.sp)
                }
            }
        }
        item { SectionTitle("Твоя музыка") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CollectionCard("На устройстве", when { !permissionGranted -> "Нужен доступ к музыке"; loading -> "Сканируем медиатеку…"; else -> "$trackCount треков" }, Modifier.weight(1f), Color(0xFF7D2024), onClick = if (permissionGranted) onRefresh else onOpenLibrary)
                CollectionCard("Любимые", "$favoriteCount треков", Modifier.weight(1f), Color(0xFF333333), onClick = onOpenLibrary)
            }
        }
        if (recentTracks.isNotEmpty()) {
            item { SectionTitle("Недавно слушали") }
            itemsIndexed(recentTracks.take(5)) { _, track ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPlayRecent(track) }.padding(vertical = 7.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArtwork(track, Modifier.size(44.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Icon(Icons.Default.PlayArrow, "Слушать", tint = Accent)
                }
            }
        }
        item { SectionTitle("Подключённые сервисы") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ServiceRow("Spotify", "Интеграция будет через официальный API", "●", Color(0xFF1DB954))
                ServiceRow("SoundCloud", "Подключение в следующем этапе", "◉", Color(0xFFFF6A2B))
                ServiceRow("YouTube Music", "Изучаем доступные способы интеграции", "▶", Color(0xFFFF4545))
            }
        }
    }
}

@Composable
private fun LibraryScreen(tracks: List<AudioTrack>, loading: Boolean, granted: Boolean, favorites: Set<String>, onRequestPermission: () -> Unit, onRefresh: () -> Unit, onPlay: (Int) -> Unit, onToggleFavorite: (AudioTrack) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("Название") }
    var onlyFavorites by remember { mutableStateOf(false) }
    val visibleTracks = remember(tracks, favorites, onlyFavorites, sortMode) {
        val source = if (onlyFavorites) tracks.filter { it.id.toString() in favorites } else tracks
        when (sortMode) {
            "Исполнитель" -> source.sortedBy { it.artist.lowercase() }
            "Альбом" -> source.sortedBy { it.album.lowercase() }
            "Длительность" -> source.sortedBy { it.duration }
            else -> source.sortedBy { it.title.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Медиатека", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("${tracks.size} треков на устройстве", color = Muted, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onlyFavorites = !onlyFavorites }) { Icon(Icons.Default.Favorite, "Избранное", tint = if (onlyFavorites) Accent else Muted) }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Сортировка", tint = Color.White) }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf("Название", "Исполнитель", "Альбом", "Длительность").forEach { mode ->
                            DropdownMenuItem(text = { Text(mode) }, onClick = { sortMode = mode; sortMenu = false })
                        }
                    }
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Обновить", tint = Color.White) }
            }
        }
        Spacer(Modifier.height(18.dp))
        when {
            !granted -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.LibraryMusic, null, tint = Accent, modifier = Modifier.size(34.dp))
                Text("Разреши доступ к музыке", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Net.music прочитает аудиофайлы на устройстве, чтобы показать их в медиатеке.", color = Muted)
                Button(onClick = onRequestPermission) { Text("Разрешить доступ") }
            }
            loading -> CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.CenterHorizontally))
            tracks.isEmpty() -> Text("Музыка не найдена. Добавь аудиофайлы на устройство и обнови список.", color = Muted)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(visibleTracks) { _, track ->
                    val originalIndex = tracks.indexOfFirst { it.id == track.id }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPlay(originalIndex) }.padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArtwork(track, Modifier.size(52.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        Text(formatTime(track.duration), color = Muted, fontSize = 11.sp)
                        IconButton(onClick = { onToggleFavorite(track) }) {
                            Icon(Icons.Default.Favorite, "В избранное", tint = if (track.id.toString() in favorites) Color(0xFFE45B68) else Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(tracks: List<AudioTrack>, favorites: Set<String>, onPlay: (Int) -> Unit, onToggleFavorite: (AudioTrack) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = tracks.withIndex().filter { it.value.title.contains(query, true) || it.value.artist.contains(query, true) || it.value.album.contains(query, true) }
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("Поиск", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Название, артист или альбом") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Accent, unfocusedBorderColor = Panel))
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            itemsIndexed(results) { _, item ->
                Row(Modifier.fillMaxWidth().clickable { onPlay(item.index) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArtwork(item.value, Modifier.size(46.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(item.value.title, color = Color.White, maxLines = 1)
                        Text("${item.value.artist} · ${item.value.album}", color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    IconButton(onClick = { onToggleFavorite(item.value) }) { Icon(Icons.Default.Favorite, "В избранное", tint = if (item.value.id.toString() in favorites) Color(0xFFE45B68) else Muted) }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Настройки", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        listOf("Подключённые сервисы", "Качество звука", "Оформление", "Хранилище", "О приложении").forEach {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(it, color = Color.White, fontSize = 16.sp)
                Text("›", color = Muted, fontSize = 22.sp)
            }
        }
        Text("Net.music · версия 0.2.0", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PlayerScreen(player: Player?, title: String, artist: String, isPlaying: Boolean, onClose: () -> Unit) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player, isPlaying) {
        while (true) {
            position = player?.currentPosition ?: 0L
            duration = (player?.duration ?: 0L).coerceAtLeast(0L)
            kotlinx.coroutines.delay(500)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("⌄", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable { onClose() })
            Text("СЕЙЧАС ИГРАЕТ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Text("•••", color = Color.White)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF6A343B), Color(0xFF24202A), Color(0xFF101010)))), contentAlignment = Alignment.Center) {
                Text("NET", color = Color.White.copy(alpha = .85f), fontSize = 64.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            }
            Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(artist, color = Muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Slider(value = if (duration > 0) position.coerceAtMost(duration).toFloat() else 0f, onValueChange = { player?.seekTo(it.toLong()) }, valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()), colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = Muted, fontSize = 12.sp)
                Text(formatTime(duration), color = Muted, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                Box(Modifier.size(70.dp).clip(CircleShape).background(Accent).clickable { if (player?.isPlaying == true) player.pause() else player?.play() }, contentAlignment = Alignment.Center) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            }
        }
    }
}

@Composable
private fun MiniPlayer(title: String, artist: String, isPlaying: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF202020)).clickable { onOpen() }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFF8D3544), Color(0xFF302536)))), contentAlignment = Alignment.Center) { Text("N", color = Color.White, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            Text(artist, color = Muted, fontSize = 11.sp, maxLines = 1)
        }
        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.clickable { onToggle() }.size(30.dp))
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF101010)).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        Tab.entries.forEach { tab ->
            val icon = when (tab) {
                Tab.HOME -> Icons.Default.Home
                Tab.LIBRARY -> Icons.Default.Folder
                Tab.SEARCH -> Icons.Default.Search
                Tab.SETTINGS -> Icons.Default.Settings
            }
            Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onSelect(tab) }.padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = tab.title, tint = if (selected == tab) Color.White else Muted, modifier = Modifier.size(23.dp))
                Text(tab.title, color = if (selected == tab) Color.White else Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun AlbumArtwork(track: AudioTrack, modifier: Modifier = Modifier) {
    val artwork = if (track.albumId > 0) Uri.parse("content://media/external/audio/albumart/${track.albumId}") else null
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Panel), contentAlignment = Alignment.Center) {
        if (artwork != null) {
            AsyncImage(model = artwork, contentDescription = "Обложка ${track.album}", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        } else {
            Icon(Icons.Default.MusicNote, null, tint = Accent)
        }
    }
}

@Composable
private fun SectionTitle(title: String) { Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold) }

@Composable
private fun CollectionCard(title: String, subtitle: String, modifier: Modifier, color: Color, onClick: () -> Unit = {}) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(color).clickable { onClick() }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Favorite, null, tint = Color.White.copy(alpha = .9f), modifier = Modifier.size(22.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(subtitle, color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
    }
}

@Composable
private fun ServiceRow(name: String, status: String, mark: String, color: Color) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(color.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Text(mark, color = color, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(status, color = Muted, fontSize = 11.sp)
        }
    }
}

private fun cleanMediaText(value: String): String {
    if (value.isBlank()) return ""
    var decoded = value.trim()
    repeat(2) {
        val next = Html.fromHtml(decoded, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        if (next == decoded) return@repeat
        decoded = next
    }
    return decoded.replace(Regex("\\s+"), " ").trim()
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}
