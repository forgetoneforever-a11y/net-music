package com.netmusic.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val MyBg = Color(0xFF080808)
private val MyPanel = Color(0xFF191919)
private val MyMuted = Color(0xFF929292)
private val MyAccent = Color(0xFFE9D9B8)

private data class UserPlaylist(val id: String, val name: String, val ids: List<String>)

@Composable
fun MyMusicScreen(
    tracks: List<AudioTrack>,
    loading: Boolean,
    granted: Boolean,
    favorites: Set<String>,
    historyIds: List<String>,
    startWithFavorites: Boolean,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Int) -> Unit,
    onToggleFavorite: (AudioTrack) -> Unit,
    onCloudMerge: (JSONObject) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("net_music_preferences", Context.MODE_PRIVATE) }
    var section by remember { mutableStateOf(if (startWithFavorites) "Избранное" else "Все") }
    var playlists by remember {
        mutableStateOf(runCatching {
            val array = JSONArray(prefs.getString("my_playlists", "[]"))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val ids = item.optJSONArray("ids") ?: JSONArray()
                    add(UserPlaylist(item.optString("id"), item.optString("name"), (0 until ids.length()).map { ids.optString(it) }))
                }
            }
        }.getOrDefault(emptyList()))
    }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var addTrack by remember { mutableStateOf<AudioTrack?>(null) }

    fun savePlaylists(value: List<UserPlaylist>) {
        playlists = value
        val array = JSONArray()
        value.forEach { item -> array.put(JSONObject().put("id", item.id).put("name", item.name).put("ids", JSONArray(item.ids))) }
        prefs.edit().putString("my_playlists", array.toString()).apply()
    }

    val downloads = tracks.filter { it.uri.scheme == "file" || it.artist == "Загружено в NET MUSIC" }
    val history = historyIds.mapNotNull { id -> tracks.find { it.id.toString() == id } }.distinctBy { it.id }
    val activePlaylist = playlists.find { it.id == selectedPlaylist }
    val visible = when (section) {
        "Загрузки" -> downloads
        "Избранное" -> tracks.filter { it.id.toString() in favorites }
        "История" -> history
        "Плейлисты" -> activePlaylist?.ids?.mapNotNull { id -> tracks.find { it.id.toString() == id } }.orEmpty()
        else -> tracks
    }

    val favoriteJson = remember(tracks, favorites) {
        JSONArray().apply { tracks.filter { it.id.toString() in favorites }.forEach { put(JSONObject().put("id", it.id.toString()).put("title", it.title).put("artist", it.artist)) } }.toString()
    }
    val historyJson = remember(history) {
        JSONArray().apply { history.forEachIndexed { i, t -> put(JSONObject().put("id", t.id.toString()).put("title", t.title).put("artist", t.artist).put("order", i)) } }.toString()
    }
    val downloadJson = remember(downloads) {
        JSONArray().apply { downloads.forEach { put(JSONObject().put("id", it.id.toString()).put("title", it.title).put("artist", it.artist)) } }.toString()
    }
    val playlistJson = remember(playlists) {
        JSONArray().apply { playlists.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("trackIds", JSONArray(it.ids))) } }.toString()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("МОЯ КОЛЛЕКЦИЯ", color = MyAccent, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text("Моя музыка", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold)
                Text(tracks.size.toString() + " треков · " + playlists.size + " плейлистов", color = MyMuted, fontSize = 12.sp)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Обновить", tint = MyAccent) }
        }
        Spacer(Modifier.height(14.dp))
        CloudAccountCard(favoriteJson, historyJson, playlistJson, downloadJson) { cloud ->
            onCloudMerge(cloud)
            val cloudPlaylists = cloud.optJSONArray("playlists") ?: JSONArray()
            val parsed = buildList {
                for (i in 0 until cloudPlaylists.length()) {
                    val item = cloudPlaylists.optJSONObject(i) ?: continue
                    val ids = item.optJSONArray("trackIds") ?: item.optJSONArray("ids") ?: JSONArray()
                    add(UserPlaylist(item.optString("id"), item.optString("name"), (0 until ids.length()).map { ids.optString(it) }))
                }
            }
            if (parsed.isNotEmpty()) savePlaylists((playlists + parsed).distinctBy { it.id })
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("БИБЛИОТЕКА", color = MyMuted, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { createDialog = true }) { Icon(Icons.Default.Add, null, tint = MyAccent); Text("Плейлист", color = MyAccent) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("Все", "Загрузки", "Избранное").forEach { name ->
                FilterChip(selected = section == name, onClick = { section = name; selectedPlaylist = null }, label = { Text(name, fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MyAccent, selectedLabelColor = Color.Black, labelColor = MyMuted))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("История", "Плейлисты").forEach { name ->
                FilterChip(selected = section == name, onClick = { section = name; selectedPlaylist = null }, label = { Text(name, fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MyAccent, selectedLabelColor = Color.Black, labelColor = MyMuted))
            }
        }

        if (section == "Плейлисты" && activePlaylist == null) {
            if (playlists.isEmpty()) EmptyMyMusic("Плейлистов пока нет", "Создай подборку для любимой музыки.", Icons.Default.QueueMusic)
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(playlists, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MyPanel).clickable { selectedPlaylist = item.id }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF34272C)), contentAlignment = Alignment.Center) { Icon(Icons.Default.QueueMusic, null, tint = MyAccent) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(item.ids.size.toString() + " треков", color = MyMuted, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MyMuted)
                    }
                }
            }
        } else {
            if (activePlaylist != null && section == "Плейлисты") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedPlaylist = null }) { Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White) }
                    Text(activePlaylist.name, color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { savePlaylists(playlists.filterNot { it.id == activePlaylist.id }); selectedPlaylist = null }) { Icon(Icons.Default.DeleteOutline, "Удалить", tint = MyMuted) }
                }
            }
            when {
                !granted && section == "Все" -> EmptyMyMusic("Разреши доступ к музыке", "NET MUSIC покажет аудиофайлы с твоего устройства.", Icons.Default.LibraryMusic, onRequestPermission)
                loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MyAccent) }
                visible.isEmpty() -> EmptyMyMusic(
                    when (section) { "Загрузки" -> "Загрузок пока нет"; "Избранное" -> "Избранное пока пусто"; "История" -> "История пока пуста"; else -> "В этой подборке пока пусто" },
                    when (section) { "Загрузки" -> "Скачанные треки будут доступны офлайн."; "Избранное" -> "Нажимай на сердечко рядом с треком."; "История" -> "Недавно прослушанное появится здесь."; else -> "Добавляй сюда композиции из медиатеки." },
                    Icons.Default.MusicNote
                )
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(visible, key = { it.id }) { track ->
                        val index = tracks.indexOfFirst { it.id == track.id }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { if (index >= 0) onPlay(index) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(47.dp).clip(RoundedCornerShape(11.dp)).background(MyPanel), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = MyAccent) }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(track.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(track.artist, color = MyMuted, fontSize = 11.sp, maxLines = 1)
                                if (track.uri.scheme == "file") Text("ОФЛАЙН · НА УСТРОЙСТВЕ", color = MyAccent, fontSize = 8.sp)
                            }
                            IconButton(onClick = { addTrack = track }) { Icon(Icons.Default.PlaylistAdd, "В плейлист", tint = MyMuted) }
                            IconButton(onClick = { onToggleFavorite(track) }) { Icon(Icons.Default.Favorite, "Избранное", tint = if (track.id.toString() in favorites) Color(0xFFE45B68) else MyMuted) }
                        }
                    }
                }
            }
        }
    }

    if (createDialog) AlertDialog(
        onDismissRequest = { createDialog = false },
        title = { Text("Новый плейлист") },
        text = { OutlinedTextField(value = playlistName, onValueChange = { playlistName = it.take(40) }, label = { Text("Название") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val name = playlistName.trim()
            if (name.isNotEmpty()) {
                savePlaylists(playlists + UserPlaylist(java.util.UUID.randomUUID().toString(), name, emptyList()))
                playlistName = ""
                section = "Плейлисты"
            }
            createDialog = false
        }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Отмена") } },
        containerColor = MyPanel, titleContentColor = Color.White, textContentColor = MyMuted
    )

    val pending = addTrack
    if (pending != null) AlertDialog(
        onDismissRequest = { addTrack = null },
        title = { Text("Добавить в плейлист") },
        text = { if (playlists.isEmpty()) Text("Сначала создай плейлист.") else Column { playlists.forEach { p ->
            TextButton(onClick = {
                savePlaylists(playlists.map { if (it.id == p.id) it.copy(ids = (it.ids + pending.id.toString()).distinct()) else it })
                addTrack = null
            }) { Text(p.name) }
        } } },
        confirmButton = { TextButton(onClick = { addTrack = null }) { Text("Закрыть") } },
        containerColor = MyPanel, titleContentColor = Color.White, textContentColor = MyMuted
    )
}

@Composable
private fun EmptyMyMusic(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp).clip(RoundedCornerShape(20.dp)).background(MyPanel).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MyAccent, modifier = Modifier.size(32.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MyMuted, fontSize = 12.sp)
        if (action != null) Button(onClick = action) { Text("Разрешить доступ") }
    }
}

@Composable
private fun CloudAccountCard(favorites: String, history: String, playlists: String, downloads: String, onCloudMerge: (JSONObject) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("net_music_cloud", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var registerMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(prefs.getString("email", "").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(prefs.getString("token", "").orEmpty()) }
    var accountName by remember { mutableStateOf(prefs.getString("display_name", "").orEmpty()) }
    var status by remember { mutableStateOf("Синхронизация библиотеки между устройствами") }
    var busy by remember { mutableStateOf(false) }
    var lastSync by remember { mutableStateOf("") }

    suspend fun api(path: String, method: String = "GET", body: JSONObject? = null, auth: String = token): JSONObject = withContext(Dispatchers.IO) {
        val c = URL("https://net-music.onrender.com" + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 12000
        c.readTimeout = 15000
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json") }
        if (auth.isNotBlank()) c.setRequestProperty("Authorization", "Bearer " + auth)
        try {
            if (body != null) c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (code !in 200..299) throw IllegalStateException(json.optString("message").ifBlank { "Ошибка сервера: " + code })
            json
        } finally { c.disconnect() }
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MyPanel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF34302A)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Cloud, null, tint = MyAccent) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(if (token.isBlank()) "NET MUSIC Cloud" else accountName.ifBlank { "Твой аккаунт" }, color = Color.White, fontWeight = FontWeight.Bold)
                Text(if (token.isBlank()) "Облако · синхронизация данных" else email, color = MyMuted, fontSize = 10.sp)
            }
            Text(if (expanded) "Свернуть" else "Открыть", color = MyAccent, fontSize = 11.sp)
        }
        if (expanded && token.isBlank()) {
            Row {
                TextButton(onClick = { registerMode = false }) { Text("Войти", color = if (!registerMode) MyAccent else MyMuted) }
                TextButton(onClick = { registerMode = true }) { Text("Регистрация", color = if (registerMode) MyAccent else MyMuted) }
            }
            if (registerMode) OutlinedTextField(displayName, { displayName = it.take(48) }, modifier = Modifier.fillMaxWidth(), label = { Text("Имя") }, singleLine = true)
            OutlinedTextField(email, { email = it.take(254) }, modifier = Modifier.fillMaxWidth(), label = { Text("Электронная почта") }, singleLine = true)
            OutlinedTextField(password, { password = it.take(128) }, modifier = Modifier.fillMaxWidth(), label = { Text("Пароль · от 8 символов") }, singleLine = true)
            Button(onClick = {
                busy = true
                scope.launch {
                    try {
                        val data = JSONObject().put("email", email).put("password", password)
                        if (registerMode) data.put("displayName", displayName)
                        val result = api(if (registerMode) "/api/cloud/register" else "/api/cloud/login", "POST", data, "")
                        token = result.optString("token")
                        val user = result.optJSONObject("user") ?: JSONObject()
                        accountName = user.optString("displayName")
                        email = user.optString("email", email)
                        prefs.edit().putString("token", token).putString("email", email).putString("display_name", accountName).apply()
                        password = ""
                        status = "Аккаунт подключён"
                    } catch (e: Exception) { status = e.message ?: "Не удалось подключиться" }
                    finally { busy = false }
                }
            }, enabled = !busy && email.isNotBlank() && password.length >= 8 && (!registerMode || displayName.trim().length >= 2), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MyAccent, contentColor = Color.Black)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black) else Text(if (registerMode) "Создать аккаунт" else "Войти в облако")
            }
        } else if (expanded) {
            Text("В облаке хранятся данные библиотеки, а аудиофайлы остаются на устройстве.", color = MyMuted, fontSize = 11.sp)
            Button(onClick = {
                busy = true
                scope.launch {
                    try {
                        val payload = JSONObject().put("favorites", JSONArray(favorites)).put("history", JSONArray(history)).put("playlists", JSONArray(playlists)).put("downloads", JSONArray(downloads))
                        val result = api("/api/cloud/sync", "POST", payload)
                        onCloudMerge(result)
                        lastSync = result.optString("updatedAt")
                        status = "Библиотека синхронизирована"
                    } catch (e: Exception) { status = e.message ?: "Ошибка синхронизации" }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MyAccent, contentColor = Color.Black)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black) else Text("Синхронизировать сейчас")
            }
            TextButton(onClick = {
                token = ""
                accountName = ""
                prefs.edit().remove("token").remove("display_name").apply()
                status = "Вы вышли из облака"
            }) { Text("Выйти из аккаунта", color = MyMuted) }
        }
        Text(status, color = MyMuted, fontSize = 10.sp)
        if (lastSync.isNotBlank()) Text("Последняя синхронизация: " + lastSync, color = MyMuted, fontSize = 9.sp)
    }
}
