package com.netmusic.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
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
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private val Background = Color(0xFF080808)
private val Panel = Color(0xFF191919)
private val Muted = Color(0xFF929292)
private val Accent = Color(0xFFE9D9B8)
private val ioExecutor = Executors.newSingleThreadExecutor()

data class AudioTrack(val id: Long, val title: String, val artist: String, val album: String, val albumId: Long, val uri: Uri, val duration: Long)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController by mutableStateOf<MediaController?>(null)
    private var spotifyStatus by mutableStateOf("Не подключён")

    private fun startSpotifyLogin() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://net-music.onrender.com/auth/spotify")))
    }

    private fun handleSpotifyCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "netmusic" || uri.host != "spotify" || uri.path != "/callback") return

        val ticket = uri.getQueryParameter("ticket")
        if (ticket.isNullOrBlank()) {
            spotifyStatus = "Не удалось получить билет авторизации"
            return
        }

        spotifyStatus = "Завершаем подключение…"
        ioExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://net-music.onrender.com/auth/spotify/exchange").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val payload = JSONObject().put("ticket", ticket).toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("Token exchange failed: HTTP $code")
                val tokens = JSONObject(body)
                val accessToken = tokens.optString("access_token")
                if (accessToken.isBlank()) throw IllegalStateException("Spotify access token is missing")

                getSharedPreferences("net_music_tokens", MODE_PRIVATE).edit()
                    .putString("spotify_access_token", accessToken)
                    .putString("spotify_refresh_token", tokens.optString("refresh_token"))
                    .putLong("spotify_expires_at", System.currentTimeMillis() + tokens.optLong("expires_in", 3600L) * 1000L)
                    .apply()
                runOnUiThread { spotifyStatus = "Spotify подключён" }
            } catch (error: Exception) {
                runOnUiThread { spotifyStatus = "Ошибка подключения Spotify" }
            } finally {
                connection?.disconnect()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyCallback(intent)
    }

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
        spotifyStatus = if (getSharedPreferences("net_music_tokens", MODE_PRIVATE)
            .getString("spotify_access_token", null).isNullOrBlank()) "Не подключён" else "Spotify подключён"
        handleSpotifyCallback(intent)
        setContent { MaterialTheme { NetMusicApp(mediaController, spotifyStatus, ::startSpotifyLogin) } }
    }
}

private enum class Tab(val title: String) {
    HOME("Домой"), SEARCH("Поиск"), LIBRARY("Файлы"), SPOTIFY("Spotify"), SETTINGS("Ещё")
}

@Composable
private fun NetMusicApp(player: Player?, spotifyStatus: String, onConnectSpotify: () -> Unit) {
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
    var openFavorites by remember { mutableStateOf(false) }
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen"
            ) { screen ->
                Box(Modifier.fillMaxSize()) {
                    if (screen == "player") {
                        PlayerScreen(player, currentTitle, currentArtist, tracks.getOrNull(currentIndex), isPlaying, { showPlayer = false })
                    } else when (Tab.valueOf(screen)) {
                        Tab.HOME -> HomeScreen(tracks.size, favoriteIds.size, recentIds.mapNotNull { id -> tracks.find { it.id.toString() == id } }, loading, permissionGranted, onOpenLibrary = { openFavorites = false; selectedTab = Tab.LIBRARY }, onOpenFavorites = { openFavorites = true; selectedTab = Tab.LIBRARY }, onOpenSpotify = { selectedTab = Tab.SEARCH }, onRefresh = { scanLibrary() }, onPlayRecent = { track -> playTrack(tracks.indexOfFirst { it.id == track.id }) })
                        Tab.SEARCH -> SearchScreen(tracks, favoriteIds, player, onPlayLocal = { playTrack(it) }, onToggleFavorite = { toggleFavorite(it) })
                        Tab.LIBRARY -> LibraryScreen(tracks, loading, permissionGranted, favoriteIds, openFavorites,
                            onRequestPermission = { requestPermission.launch(permission) },
                            onRefresh = { scanLibrary() }, onPlay = { playTrack(it) }, onToggleFavorite = { toggleFavorite(it) })
                        Tab.SPOTIFY -> SpotifyScreen(spotifyStatus, onConnectSpotify)
                        Tab.SETTINGS -> SettingsScreen(spotifyStatus, onConnectSpotify, player, tracks.size, recentIds.size, onClearHistory = { recentIds = emptyList(); preferences.edit().remove("recent_tracks").apply() }, onRefreshLibrary = { scanLibrary() })
                    }
                }
            }
            if (!showPlayer) {
                MiniPlayer(currentTitle, currentArtist, isPlaying, { player?.let { if (it.isPlaying) it.pause() else it.play() } }, { showPlayer = true })
                BottomBar(selectedTab) { selectedTab = it }
            }
        }
    }
}

@Composable
private fun HomeScreen(trackCount: Int, favoriteCount: Int, recentTracks: List<AudioTrack>, loading: Boolean, permissionGranted: Boolean, onOpenLibrary: () -> Unit, onOpenFavorites: () -> Unit, onOpenSpotify: () -> Unit, onRefresh: () -> Unit, onPlayRecent: (AudioTrack) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("NET MUSIC", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("Твоя музыка.", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                Text("Всё любимое — в одном месте", color = Muted, fontSize = 13.sp)
            }
        }
        item {
            Box(Modifier.fillMaxWidth().height(218.dp).clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF47343A), Color(0xFF242126), Color(0xFF111111))))
                .clickable { onOpenLibrary() }.padding(22.dp), contentAlignment = Alignment.BottomStart) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("YOUR SOUND · YOUR SPACE", color = Accent, fontSize = 10.sp, letterSpacing = 1.8.sp)
                    Text("Слушай своё.\nБез лишнего.", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
                    Text("Открыть медиатеку  →", color = Color.White.copy(alpha = .78f), fontSize = 13.sp)
                }
            }
        }
        item { SectionTitle("Твоя музыка") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CollectionCard("На устройстве", when { !permissionGranted -> "Нужен доступ к музыке"; loading -> "Сканируем медиатеку…"; else -> "$trackCount треков" }, Modifier.weight(1f), Color(0xFF7D2024), onClick = if (permissionGranted) onRefresh else onOpenLibrary)
                CollectionCard("Любимые", "$favoriteCount треков", Modifier.weight(1f), Color(0xFF333333), onClick = onOpenFavorites)
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
        item { SectionTitle("Музыка из каталога") }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).clickable { onOpenSpotify() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF1DB954).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    Text("S", color = Color(0xFF1DB954), fontWeight = FontWeight.Black, fontSize = 22.sp)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Audius + Jamendo", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Поиск и прослушивание прямо в NET MUSIC", color = Muted, fontSize = 11.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Muted)
            }
        }
    }
}

@Composable
private fun LibraryScreen(tracks: List<AudioTrack>, loading: Boolean, granted: Boolean, favorites: Set<String>, startWithFavorites: Boolean, onRequestPermission: () -> Unit, onRefresh: () -> Unit, onPlay: (Int) -> Unit, onToggleFavorite: (AudioTrack) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("Название") }
    var onlyFavorites by remember { mutableStateOf(startWithFavorites) }
    LaunchedEffect(startWithFavorites) { onlyFavorites = startWithFavorites }
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
                Text(if (onlyFavorites) "Любимые" else "Медиатека", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(if (onlyFavorites) "${visibleTracks.size} избранных треков" else "${tracks.size} треков на устройстве", color = Muted, fontSize = 13.sp)
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
            onlyFavorites && visibleTracks.isEmpty() -> Text("Здесь пока пусто. Нажми на сердечко рядом с треком, чтобы добавить его в любимые.", color = Muted)
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

private data class CatalogTrack(val provider: String, val title: String, val artist: String, val artwork: String, val duration: Long, val streamUrl: String)

@Composable
private fun SearchScreen(tracks: List<AudioTrack>, favorites: Set<String>, player: Player?, onPlayLocal: (Int) -> Unit, onToggleFavorite: (AudioTrack) -> Unit) {
    var query by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf<List<CatalogTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ищи музыку сразу в Audius и Jamendo.") }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val term = query.trim()
        if (term.length < 2) {
            remote = emptyList()
            loading = false
            searched = false
            status = "Ищи музыку сразу в Audius и Jamendo."
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(400)
        loading = true
        searched = true
        try {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val encoded = java.net.URLEncoder.encode(term, "UTF-8")
                val connection = URL("https://net-music.onrender.com/api/catalog/search?q=" + encoded + "&limit=24").openConnection() as HttpURLConnection
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                try {
                    val code = connection.responseCode
                    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) throw IllegalStateException("HTTP " + code)
                    val json = JSONObject(body)
                    val array = json.optJSONArray("tracks") ?: org.json.JSONArray()
                    val items = buildList {
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i) ?: continue
                            val stream = item.optString("streamUrl")
                            if (stream.isBlank()) continue
                            add(CatalogTrack(item.optString("provider"), item.optString("title", "Без названия"), item.optString("artist", "Неизвестный исполнитель"), item.optString("artwork"), item.optLong("duration", 0L) * 1000L, stream))
                        }
                    }
                    val providers = json.optJSONArray("providers")
                    val message = if (providers == null) "" else (0 until providers.length()).mapNotNull { i ->
                        providers.optJSONObject(i)?.let { p ->
                            val name = p.optString("name")
                            val state = p.optString("status")
                            if (state == "not_configured") name + ": ожидает настройки"
                            else if (state == "unavailable") name + ": временно недоступен"
                            else name + ": " + p.optInt("count") + " треков"
                        }
                    }.joinToString(" · ")
                    items to message
                } finally { connection.disconnect() }
            }
            if (query.trim() == term) {
                remote = result.first
                status = result.second.ifBlank { "Каталоги проверены." }
            }
        } catch (_: Exception) {
            remote = emptyList()
            status = "Не удалось связаться с каталогом. Попробуй ещё раз."
        } finally { loading = false }
    }

    val local = tracks.withIndex().filter { query.isNotBlank() && (it.value.title.contains(query, true) || it.value.artist.contains(query, true) || it.value.album.contains(query, true)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp)) {
        Text("Поиск", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Один поиск · несколько музыкальных каталогов", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Трек, исполнитель или жанр") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Accent, unfocusedBorderColor = Panel, cursorColor = Accent))
        Spacer(Modifier.height(12.dp))
        if (query.length < 2) {
            Text("Например: phonk, ambient, имя исполнителя или название песни.", color = Muted, fontSize = 12.sp)
        } else {
            Text("МУЗЫКА ИЗ КАТАЛОГОВ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Text(status, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 7.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Panel)
            else if (remote.isEmpty() && searched) Text("Ничего не найдено. Попробуй другой запрос.", color = Muted, fontSize = 13.sp)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(remote) { _, track ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).clickable {
                        val p = player ?: return@clickable
                        val metadata = MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).build()
                        p.setMediaItem(MediaItem.Builder().setUri(track.streamUrl).setMediaMetadata(metadata).build())
                        p.prepare()
                        p.play()
                    }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (track.artwork.isNotBlank()) AsyncImage(model = track.artwork, contentDescription = "Обложка", modifier = Modifier.size(50.dp).clip(RoundedCornerShape(11.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Box(Modifier.size(50.dp).clip(RoundedCornerShape(11.dp)).background(Panel), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Accent) }
                        Column(Modifier.weight(1f).padding(start = 11.dp)) {
                            Text(track.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(track.artist, color = Muted, fontSize = 11.sp, maxLines = 1)
                            Text(track.provider, color = Accent, fontSize = 9.sp)
                        }
                        Text(if (track.duration > 0) formatTime(track.duration) else "", color = Muted, fontSize = 10.sp)
                        Icon(Icons.Default.PlayArrow, "Слушать", tint = Accent)
                    }
                }
            }
            if (local.isNotEmpty()) {
                Text("НА ТВОЁМ УСТРОЙСТВЕ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 10.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    itemsIndexed(local) { _, item ->
                        Row(Modifier.fillMaxWidth().clickable { onPlayLocal(item.index) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AlbumArtwork(item.value, Modifier.size(44.dp))
                            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                Text(item.value.title, color = Color.White, maxLines = 1, fontSize = 13.sp)
                                Text(item.value.artist, color = Muted, fontSize = 11.sp, maxLines = 1)
                            }
                            IconButton(onClick = { onToggleFavorite(item.value) }) { Icon(Icons.Default.Favorite, "В избранное", tint = if (item.value.id.toString() in favorites) Color(0xFFE45B68) else Muted) }
                        }
                    }
                }
            }
        }
    }
}

private data class SpotifyTrack(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val imageUrl: String,
    val previewUrl: String?,
    val spotifyUri: String
)

@Composable
private fun SpotifyScreen(spotifyStatus: String, onConnectSpotify: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("net_music_tokens", android.content.Context.MODE_PRIVATE) }
    val accessToken = remember(spotifyStatus) { prefs.getString("spotify_access_token", null).orEmpty() }
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(if (accessToken.isBlank()) "Подключи Spotify, чтобы искать музыку из каталога." else "Ищи любимые треки, исполнителей и альбомы.") }
    var searched by remember { mutableStateOf(false) }

    fun searchSpotify() {
        if (accessToken.isBlank()) {
            message = "Сначала подключи аккаунт Spotify."
            return
        }
        if (query.isBlank()) {
            message = "Введи название трека или исполнителя."
            return
        }
        loading = true
        searched = true
        ioExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                connection = (URL("https://api.spotify.com/v1/search?q=$encoded&type=track&limit=10").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Authorization", "Bearer $accessToken")
                }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code == 401) throw IllegalStateException("Сессия Spotify истекла. Подключи аккаунт заново.")
                if (code == 403) throw IllegalStateException("Spotify отклонил запрос (403). Добавь этот аккаунт в Spotify Developer Dashboard → Users and Access и проверь, что у владельца приложения активен Premium.")
                if (code !in 200..299) throw IllegalStateException("Spotify вернул ошибку HTTP $code.")
                val items = JSONObject(body).optJSONObject("tracks")?.optJSONArray("items")
                val found = mutableListOf<SpotifyTrack>()
                if (items != null) for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val artists = item.optJSONArray("artists")
                    val artistNames = mutableListOf<String>()
                    if (artists != null) for (j in 0 until artists.length()) {
                        artists.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(artistNames::add)
                    }
                    val album = item.optJSONObject("album")
                    val images = album?.optJSONArray("images")
                    val image = images?.optJSONObject(0)?.optString("url").orEmpty()
                    found += SpotifyTrack(
                        item.optString("id"), item.optString("name"),
                        artistNames.joinToString(", ").ifBlank { "Неизвестный исполнитель" },
                        album?.optString("name").orEmpty(), image,
                        item.optString("preview_url").takeIf { it.isNotBlank() && it != "null" },
                        item.optString("uri")
                    )
                }
                Handler(Looper.getMainLooper()).post {
                    tracks = found
                    message = if (found.isEmpty()) "Ничего не найдено. Попробуй другой запрос." else "Найдено треков: ${found.size}"
                    loading = false
                }
            } catch (error: Exception) {
                Handler(Looper.getMainLooper()).post {
                    tracks = emptyList()
                    message = error.message ?: "Не удалось выполнить поиск."
                    loading = false
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp)) {
        Text("Spotify", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Музыкальный каталог", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        if (accessToken.isBlank()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("♫", color = Color(0xFF1DB954), fontSize = 42.sp, fontWeight = FontWeight.Bold)
                Text("Подключи Spotify", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("После авторизации здесь появятся поиск по каталогу Spotify и карточки найденных треков.", color = Muted)
                Button(onClick = onConnectSpotify, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black)) {
                    Text("Подключить аккаунт")
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF1DB954)))
                Text(" Аккаунт подключён", color = Color(0xFF1DB954), fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Трек или исполнитель") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { searchSpotify() }, enabled = !loading) {
                        Icon(Icons.Default.Search, "Искать", tint = Color.White)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF1DB954), unfocusedBorderColor = Panel,
                    cursorColor = Color(0xFF1DB954)
                )
            )
            Spacer(Modifier.height(10.dp))
            Text(message, color = Muted, fontSize = 12.sp)
            if (loading) {
                CircularProgressIndicator(color = Color(0xFF1DB954), modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(tracks) { _, track ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).clickable {
                            val target = track.spotifyUri.takeIf { it.startsWith("spotify:") }?.let { Uri.parse(it) }
                                ?: Uri.parse("https://open.spotify.com/track/${track.id}")
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                        }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (track.imageUrl.isNotBlank()) {
                                AsyncImage(model = track.imageUrl, contentDescription = track.album, modifier = Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)))
                            } else {
                                Box(Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF292929)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MusicNote, null, tint = Color(0xFF1DB954))
                                }
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(track.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
                                Text(track.album, color = Muted.copy(alpha = .7f), fontSize = 10.sp, maxLines = 1)
                                Text("Открыть в Spotify ↗", color = Color(0xFF1DB954), fontSize = 10.sp)
                            }
                            Icon(Icons.Default.OpenInNew, "Открыть в Spotify", tint = Color(0xFF1DB954))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    spotifyStatus: String,
    onConnectSpotify: () -> Unit,
    player: Player?,
    trackCount: Int,
    recentCount: Int,
    onClearHistory: () -> Unit,
    onRefreshLibrary: () -> Unit
) {
    var dialog by remember { mutableStateOf("") }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var speed by remember(player) { mutableFloatStateOf(player?.playbackParameters?.speed ?: 1f) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp)) {
        Text("Ещё", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Управление приложением", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Spotify", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(spotifyStatus, color = if (spotifyStatus == "Spotify подключён") Color(0xFF1DB954) else Muted, fontSize = 11.sp)
            }
            Text("Каталог, поиск и открытие найденных треков в Spotify.", color = Muted, fontSize = 12.sp)
            Button(onClick = onConnectSpotify, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black)) {
                Text(if (spotifyStatus == "Spotify подключён") "Переподключить аккаунт" else "Подключить Spotify")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("ПРОИГРЫВАТЕЛЬ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        SettingsAction(Icons.Default.Speed, "Скорость воспроизведения", "${"%.2f".format(speed)}×") { dialog = "speed" }
        SettingsAction(Icons.Default.Refresh, "Обновить медиатеку", "$trackCount треков") { onRefreshLibrary() }
        Spacer(Modifier.height(4.dp))
        Text("ПРИЛОЖЕНИЕ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        SettingsAction(Icons.Default.History, "История прослушивания", "$recentCount недавно включённых") { dialog = "history" }
        SettingsAction(Icons.Default.Info, "О приложении", "NET MUSIC · версия 0.2.0") { dialog = "about" }
        Spacer(Modifier.height(10.dp))
        Text("NET MUSIC · локальная музыка + каталог Spotify", color = Muted, fontSize = 11.sp)
    }

    if (dialog == "speed") {
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Скорость воспроизведения") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            player?.setPlaybackSpeed(value)
                            speed = value
                            dialog = ""
                        }.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${"%.2f".format(value)}×", color = Color.White)
                            if (kotlin.math.abs(speed - value) < 0.01f) Icon(Icons.Default.Check, null, tint = Accent)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Закрыть") } },
            containerColor = Panel, titleContentColor = Color.White, textContentColor = Muted
        )
    }
    if (dialog == "history") {
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("История прослушивания") },
            text = { Text("Сохранено недавних треков: $recentCount. Очистка удалит только историю, избранное и аудиофайлы останутся на месте.") },
            confirmButton = { TextButton(onClick = { showClearConfirmation = true; dialog = "" }) { Text("Очистить историю") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Назад") } },
            containerColor = Panel, titleContentColor = Color.White, textContentColor = Muted
        )
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Очистить историю?") },
            text = { Text("Это действие нельзя отменить. Избранные треки не будут удалены.") },
            confirmButton = { TextButton(onClick = { onClearHistory(); showClearConfirmation = false }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("Отмена") } },
            containerColor = Panel, titleContentColor = Color.White, textContentColor = Muted
        )
    }
    if (dialog == "about") {
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("NET MUSIC") },
            text = { Text("Музыкальный плеер для локальных аудиофайлов с поиском каталога Spotify. Spotify используется для поиска и открытия треков в официальном приложении или веб-плеере; воспроизведение локальных файлов работает независимо.") },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Понятно") } },
            containerColor = Panel, titleContentColor = Color.White, textContentColor = Muted
        )
    }
}

@Composable
private fun SettingsAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun PlayerScreen(player: Player?, title: String, artist: String, track: AudioTrack?, isPlaying: Boolean, onClose: () -> Unit) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showQueue by remember { mutableStateOf(false) }
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
            Text("Очередь", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { showQueue = true })
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (track != null) {
                AlbumArtwork(track, Modifier.fillMaxWidth().heightIn(max = 260.dp).aspectRatio(1f))
            } else {
                Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).aspectRatio(1f).clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF6A343B), Color(0xFF24202A), Color(0xFF101010)))), contentAlignment = Alignment.Center) {
                    Text("NET", color = Color.White.copy(alpha = .85f), fontSize = 64.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
                }
            }
            Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(artist, color = Muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Slider(value = if (duration > 0) position.coerceAtMost(duration).toFloat() else 0f, onValueChange = { player?.seekTo(it.toLong()) }, enabled = duration > 0L, valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()), colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = Muted, fontSize = 12.sp)
                Text(formatTime(duration), color = Muted, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player?.shuffleModeEnabled = !(player?.shuffleModeEnabled ?: false) }, enabled = (player?.mediaItemCount ?: 0) > 0) {
                    Icon(Icons.Default.Shuffle, "Перемешать", tint = if (player?.shuffleModeEnabled == true) Accent else Muted)
                }
                IconButton(onClick = { player?.seekToPreviousMediaItem() }, enabled = (player?.mediaItemCount ?: 0) > 0) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                Box(Modifier.size(66.dp).clip(CircleShape).background(Accent).clickable { if (player?.isPlaying == true) player.pause() else player?.play() }, contentAlignment = Alignment.Center) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { player?.seekToNextMediaItem() }, enabled = (player?.mediaItemCount ?: 0) > 0) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = { player?.repeatMode = if (player?.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE }, enabled = (player?.mediaItemCount ?: 0) > 0) {
                    Icon(Icons.Default.RepeatOne, "Повтор трека", tint = if (player?.repeatMode == Player.REPEAT_MODE_ONE) Accent else Muted)
                }
            }
        }
    }
    if (showQueue) {
        val queueItems = (0 until (player?.mediaItemCount ?: 0)).map { index ->
            val metadata = player?.getMediaItemAt(index)?.mediaMetadata
            Triple(index, metadata?.title?.toString().orEmpty().ifBlank { "Трек " + (index + 1) }, metadata?.artist?.toString().orEmpty())
        }
        AlertDialog(
            onDismissRequest = { showQueue = false },
            title = { Text("Очередь воспроизведения") },
            text = {
                if (queueItems.isEmpty()) Text("Очередь пуста")
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(queueItems) { _, item ->
                        Row(Modifier.fillMaxWidth().clickable {
                            player?.seekToDefaultPosition(item.first)
                            player?.play()
                            showQueue = false
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (item.first == player?.currentMediaItemIndex) Icons.Default.VolumeUp else Icons.Default.MusicNote, null, tint = if (item.first == player?.currentMediaItemIndex) Accent else Muted)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(item.second, color = Color.White, maxLines = 1)
                                Text(item.third, color = Muted, fontSize = 11.sp, maxLines = 1)
                            }
                            if (item.first == player?.currentMediaItemIndex) Text("Сейчас", color = Accent, fontSize = 10.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Закрыть") } },
            containerColor = Panel,
            titleContentColor = Color.White,
            textContentColor = Muted
        )
    }
}

@Composable
private fun MiniPlayer(title: String, artist: String, isPlaying: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF1C1C1C)).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(Color(0xFF70434B), Color(0xFF29252D)))).clickable { onOpen() }, contentAlignment = Alignment.Center) { Text("N", color = Color.White, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f).padding(start = 11.dp).clickable { onOpen() }) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            Text(artist, color = Muted, fontSize = 10.sp, maxLines = 1)
        }
        IconButton(onClick = onToggle, enabled = title != "Выбери свой звук") { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Воспроизведение", tint = if (title != "Выбери свой звук") Color.White else Muted) }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF101010)).padding(horizontal = 5.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Tab.entries.forEach { tab ->
            val icon = when (tab) {
                Tab.HOME -> Icons.Default.Home
                Tab.SEARCH -> Icons.Default.Search
                Tab.LIBRARY -> Icons.Default.Folder
                Tab.SPOTIFY -> Icons.Default.MusicNote
                Tab.SETTINGS -> Icons.Default.Settings
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onSelect(tab) }.padding(horizontal = 2.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected == tab) Color(0xFF303030) else Color.Transparent).padding(horizontal = 13.dp, vertical = 5.dp)) {
                    Icon(icon, contentDescription = tab.title, tint = if (selected == tab) Accent else Muted, modifier = Modifier.size(21.dp))
                }
                Text(tab.title, color = if (selected == tab) Color.White else Muted, fontSize = 9.sp, maxLines = 1)
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
