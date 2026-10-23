package com.baratovfk.pixeltune

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

private val Night = Color(0xFF0D0F14)
private val Panel = Color(0xFF191D26)
private val Lime = Color(0xFFB8F36B)
private val Soft = Color(0xFF9BA1B0)

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    private var songs by mutableStateOf<List<Song>>(emptyList())
    private var permissionGranted by mutableStateOf(false)
    private lateinit var player: ExoPlayer
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) loadSongs()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        permissionGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) loadSongs() else permissionLauncher.launch(permission)
        setContent {
            PixelTuneScreen(
                songs = songs,
                isPlaying = player.isPlaying,
                onPlay = { index ->
                    if (index in songs.indices) {
                        player.setMediaItem(MediaItem.fromUri(songs[index].uri))
                        player.prepare()
                        player.play()
                    }
                },
                onToggle = { if (player.isPlaying) player.pause() else player.play() },
                onNext = { if (songs.isNotEmpty()) { player.seekToNextMediaItem(); if (!player.isPlaying) player.play() } },
                onPrevious = { if (songs.isNotEmpty()) { player.seekToPreviousMediaItem(); if (!player.isPlaying) player.play() } },
                onRequestPermission = {
                    if (!permissionGranted) permissionLauncher.launch(permission)
                    else loadSongs()
                }
            )
        }
    }

    private fun loadSongs() {
        val found = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} ASC"
        val cursor: Cursor? = contentResolver.query(collection, projection, selection, null, sort)
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val title = it.getString(titleCol) ?: "Без названия"
                val artist = it.getString(artistCol) ?: "Неизвестный исполнитель"
                found += Song(id, title, artist, ContentUris.withAppendedId(collection, id))
            }
        }
        songs = found
        player.clearMediaItems()
        if (found.isNotEmpty()) {
            found.forEach { player.addMediaItem(MediaItem.fromUri(it.uri)) }
            player.prepare()
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

@Composable
private fun PixelTuneScreen(
    songs: List<Song>,
    isPlaying: Boolean,
    onPlay: (Int) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRequestPermission: () -> Unit
) {
    var tab by remember { mutableStateOf("Музыка") }
    var current by remember { mutableIntStateOf(0) }
    val favorites = remember { mutableStateListOf<Long>() }
    val visibleSongs = if (tab == "Избранное") songs.filter { it.id in favorites } else songs
    val selected = songs.getOrNull(current)

    MaterialTheme(colorScheme = darkColorScheme(primary = Lime, background = Night, surface = Panel)) {
        Scaffold(
            containerColor = Night,
            bottomBar = {
                Column {
                    if (selected != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp))
                                .background(Panel).clickable { tab = "Музыка" }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlbumTile("♫", Modifier.size(42.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(selected.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(selected.artist, color = Soft, fontSize = 12.sp, maxLines = 1)
                            }
                            IconButton(onClick = onToggle) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Lime) }
                        }
                    }
                    NavigationBar(containerColor = Color(0xFF12141A)) {
                        listOf("Музыка", "Избранное").forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(if (item == "Музыка") Icons.Default.LibraryMusic else Icons.Default.Favorite, contentDescription = item) },
                                label = { Text(item) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = Lime, selectedTextColor = Lime, indicatorColor = Lime.copy(alpha = .12f), unselectedIconColor = Soft, unselectedTextColor = Soft)
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(24.dp))
                Text("PIXELTUNE", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text(if (tab == "Музыка") "Твоя музыка." else "Избранное", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF37435A), Color(0xFF1B202B), Color(0xFF2E4022))))
                        .padding(20.dp)
                ) {
                    Column {
                        Text("ТВОЙ ЗВУК. ТВОИ ПРАВИЛА.", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Музыка без лишнего.", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Локальная библиотека • простой плеер", color = Soft, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${visibleSongs.size} треков", color = Color.White, modifier = Modifier.weight(1f))
                            Button(onClick = onRequestPermission, colors = ButtonDefaults.buttonColors(containerColor = Lime), shape = RoundedCornerShape(50)) {
                                Text("Обновить", color = Night)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(if (tab == "Музыка") "Все треки" else "Любимые треки", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (visibleSongs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (tab == "Избранное") "♡" else "♫", color = Lime, fontSize = 48.sp)
                            Text(if (tab == "Избранное") "Пока нет избранных треков" else "Музыка не найдена", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(if (tab == "Избранное") "Добавляй треки сердечком" else "Добавь аудиофайлы на телефон и нажми «Обновить»", color = Soft, fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(visibleSongs, key = { _, song -> song.id }) { _, song ->
                            val realIndex = songs.indexOfFirst { it.id == song.id }
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                                    current = realIndex
                                    onPlay(realIndex)
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlbumTile("♫", Modifier.size(52.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, color = if (realIndex == current) Lime else Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { if (song.id in favorites) favorites.remove(song.id) else favorites.add(song.id) }) {
                                    Icon(Icons.Default.Favorite, contentDescription = "Избранное", tint = if (song.id in favorites) Lime else Soft)
                                }
                            }
                        }
                    }
                }
                if (selected != null) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                        FilledIconButton(onClick = onToggle, modifier = Modifier.size(58.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Lime, contentColor = Night)) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = { onNext(); current = (current + 1).coerceAtMost((songs.size - 1).coerceAtLeast(0)) }) {
                            Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumTile(symbol: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF35445A), Color(0xFF252B37)))), contentAlignment = Alignment.Center) {
        Text(symbol, color = Lime, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}
