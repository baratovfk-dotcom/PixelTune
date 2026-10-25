package com.baratovfk.pixeltune

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
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
import androidx.compose.material.icons.filled.*
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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

private val Night = Color(0xFF0D0F14)
private val Panel = Color(0xFF191D26)
private val Lime = Color(0xFFB8F36B)
private val Soft = Color(0xFF9BA1B0)

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri, val dateAdded: Long)

class MainActivity : ComponentActivity() {
    private var songs by mutableStateOf<List<Song>>(emptyList())
    private var permissionGranted by mutableStateOf(false)
    private var isPlayingState by mutableStateOf(false)
    private var currentIndexState by mutableStateOf(0)
    private lateinit var player: ExoPlayer
    private lateinit var prefs: SharedPreferences
    private var equalizer: Equalizer? = null

    private var isShuffleEnabled by mutableStateOf(false)
    private var repeatModeState by mutableStateOf(Player.REPEAT_MODE_OFF)
    private var favorites = mutableStateOf(setOf<Long>())
    private var sleepTimer: CountDownTimer? = null
    private var sleepTimerMinutesLeft by mutableStateOf(0)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) loadSongs()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("pixeltune_prefs", Context.MODE_PRIVATE)
        loadFavoritesFromPrefs()

        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    isPlayingState = isPlaying
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentIndexState = player.currentMediaItemIndex.coerceAtLeast(0)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    currentIndexState = player.currentMediaItemIndex.coerceAtLeast(0)
                    isPlayingState = player.isPlaying
                }
            })
        }

        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        permissionGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) loadSongs() else permissionLauncher.launch(permission)

        setContent {
            PixelTuneScreen(
                songs = songs,
                player = player,
                currentIndex = currentIndexState,
                isPlaying = isPlayingState,
                favorites = favorites.value,
                isShuffle = isShuffleEnabled,
                repeatMode = repeatModeState,
                sleepTimerMinutes = sleepTimerMinutesLeft,
                onPlay = { index ->
                    if (index in songs.indices) {
                        player.seekTo(index, 0)
                        player.prepare()
                        player.play()
                        initEqualizer()
                    }
                },
                onToggle = { if (player.isPlaying) player.pause() else player.play() },
                onNext = {
                    if (songs.isNotEmpty()) {
                        if (player.hasNextMediaItem()) {
                            player.seekToNextMediaItem()
                        } else {
                            player.seekTo(0, 0)
                        }
                        if (!player.isPlaying) player.play()
                    }
                },
                onPrevious = {
                    if (songs.isNotEmpty()) {
                        if (player.currentPosition > 3_000L) {
                            player.seekTo(0, 0)
                        } else if (player.hasPreviousMediaItem()) {
                            player.seekToPreviousMediaItem()
                        } else {
                            player.seekTo(0, 0)
                        }
                        if (!player.isPlaying) player.play()
                    }
                },
                onToggleFavorite = { id -> toggleFavorite(id) },
                onToggleShuffle = {
                    isShuffleEnabled = !isShuffleEnabled
                    player.shuffleModeEnabled = isShuffleEnabled
                },
                onToggleRepeat = {
                    repeatModeState = when (repeatModeState) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    player.repeatMode = repeatModeState
                },
                onSetSleepTimer = { minutes -> startSleepTimer(minutes) },
                equalizer = equalizer,
                onRequestPermission = {
                    if (!permissionGranted) permissionLauncher.launch(permission)
                    else loadSongs()
                }
            )
        }
    }

    private fun loadFavoritesFromPrefs() {
        val saved = prefs.getStringSet("favs", emptySet()) ?: emptySet()
        favorites.value = saved.mapNotNull { it.toLongOrNull() }.toSet()
    }

    private fun toggleFavorite(id: Long) {
        val currentSet = favorites.value.toMutableSet()
        if (currentSet.contains(id)) currentSet.remove(id) else currentSet.add(id)
        favorites.value = currentSet
        prefs.edit().putStringSet("favs", currentSet.map { it.toString() }.toSet()).apply()
    }

    private fun initEqualizer() {
        try {
            if (equalizer == null && player.audioSessionId != android.media.audiofx.AudioEffect.ERROR_BAD_VALUE) {
                equalizer = Equalizer(0, player.audioSessionId).apply {
                    enabled = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startSleepTimer(minutes: Int) {
        sleepTimer?.cancel()
        if (minutes <= 0) {
            sleepTimerMinutesLeft = 0
            return
        }
        sleepTimerMinutesLeft = minutes
        sleepTimer = object : CountDownTimer((minutes * 60 * 1000).toLong(), 60000) {
            override fun onTick(millisUntilFinished: Long) {
                sleepTimerMinutesLeft = (millisUntilFinished / 60000).toInt() + 1
            }
            override fun onFinish() {
                player.pause()
                sleepTimerMinutesLeft = 0
            }
        }.start()
    }

    private fun loadSongs() {
        val found = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATE_ADDED)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} ASC"
        val cursor: Cursor? = contentResolver.query(collection, projection, selection, null, sort)
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dateAddedCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val title = it.getString(titleCol) ?: "Без названия"
                val artist = it.getString(artistCol) ?: "Неизвестный исполнитель"
                found += Song(id, title, artist, ContentUris.withAppendedId(collection, id), it.getLong(dateAddedCol))
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
        sleepTimer?.cancel()
        equalizer?.release()
        player.release()
        super.onDestroy()
    }
}

@Composable
private fun PixelTuneScreen(
    songs: List<Song>,
    player: ExoPlayer,
    currentIndex: Int,
    isPlaying: Boolean,
    favorites: Set<Long>,
    isShuffle: Boolean,
    repeatMode: Int,
    sleepTimerMinutes: Int,
    onPlay: (Int) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    equalizer: Equalizer?,
    onRequestPermission: () -> Unit
) {
    var tab by remember { mutableStateOf("Музыка") }
    var searchQuery by remember { mutableStateOf("") }
    var showEqualizerDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sortOrder by remember { mutableStateOf("Название") }
    var showSortMenu by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(player, isPlaying, currentIndex) {
        while (true) {
            val duration = player.duration
            if (duration > 0L) progress = (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            delay(500)
        }
    }

    val filteredSongs = songs.filter {
        it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true)
    }.let { list ->
        when (sortOrder) {
            "Исполнитель" -> list.sortedWith(compareBy<Song> { it.artist.lowercase() }.thenBy { it.title.lowercase() })
            "Недавно добавленные" -> list.sortedByDescending { it.dateAdded }
            else -> list.sortedWith(compareBy<Song> { it.title.lowercase() })
        }
    }
    val visibleSongs = if (tab == "Избранное") filteredSongs.filter { it.id in favorites } else filteredSongs
    val currentSong = songs.getOrNull(currentIndex)

    MaterialTheme(colorScheme = darkColorScheme(primary = Lime, background = Night, surface = Panel)) {
        Scaffold(
            containerColor = Night,
            bottomBar = {
                Column {
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
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("PIXELTUNE", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Text(if (tab == "Музыка") "Твоя музыка." else "Избранное", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Row {
                        IconButton(onClick = { showEqualizerDialog = true }) {
                            Icon(Icons.Default.Equalizer, contentDescription = "Эквалайзер", tint = Lime)
                        }
                        IconButton(onClick = { showSleepTimerDialog = true }) {
                            Icon(Icons.Default.Timer, contentDescription = "Таймер сна", tint = if (sleepTimerMinutes > 0) Lime else Soft)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Строка поиска
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск треков...", color = Soft) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Soft) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Lime,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Panel,
                        unfocusedContainerColor = Panel,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true
                )

                Spacer(Modifier.height(12.dp))

                // Элементы управления воспроизведением (Перемешивание, Повтор)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onToggleShuffle) {
                            Icon(Icons.Default.Shuffle, null, tint = if (isShuffle) Lime else Soft)
                        }
                        IconButton(onClick = onToggleRepeat) {
                            Icon(
                                imageVector = when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                                    else -> Icons.Default.Repeat
                                },
                                null,
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) Lime else Soft
                            )
                        }
                        if (sleepTimerMinutes > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text("Таймер: ${sleepTimerMinutes}м", color = Lime, fontSize = 12.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            TextButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = null, tint = Lime, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(sortOrder, color = Lime, fontSize = 11.sp)
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                listOf("Название", "Исполнитель", "Недавно добавленные").forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = { sortOrder = option; showSortMenu = false }
                                    )
                                }
                            }
                        }
                        Button(onClick = onRequestPermission, colors = ButtonDefaults.buttonColors(containerColor = Panel), shape = RoundedCornerShape(50)) {
                            Text("Обновить", color = Lime, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(if (tab == "Музыка") "Все треки (${visibleSongs.size})" else "Любимые треки (${visibleSongs.size})", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                if (visibleSongs.isEmpty()) {
                    Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (tab == "Избранное") "♡" else "♫", color = Lime, fontSize = 42.sp)
                            Text(if (tab == "Избранное") "Пока нет избранных" else "Музыка не найдена", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Проверь файлы на устройстве", color = Soft, fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(visibleSongs, key = { _, song -> song.id }) { _, song ->
                            val realIndex = songs.indexOfFirst { it.id == song.id }
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                                    if (realIndex != -1) onPlay(realIndex)
                                }.padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlbumTile("♫", Modifier.size(48.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onToggleFavorite(song.id) }) {
                                    Icon(
                                        imageVector = if (song.id in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Избранное",
                                        tint = if (song.id in favorites) Lime else Soft
                                    )
                                }
                            }
                        }
                    }
                }

                // Мини-плеер: текущий трек, прогресс, перемотка и транспортные кнопки
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel)
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AlbumTile("♫", Modifier.size(42.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(currentSong?.title ?: "Выбери трек", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(currentSong?.artist ?: "PixelTune", color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (currentSong != null) {
                                IconButton(onClick = { onToggleFavorite(currentSong.id) }) {
                                    Icon(
                                        if (currentSong.id in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Избранное",
                                        tint = if (currentSong.id in favorites) Lime else Soft
                                    )
                                }
                            }
                        }
                        Slider(
                            value = progress,
                            onValueChange = { progress = it },
                            onValueChangeFinished = {
                                val duration = player.duration
                                if (duration > 0L) player.seekTo((progress * duration).toLong())
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime, inactiveTrackColor = Soft.copy(alpha = .25f)),
                            modifier = Modifier.height(28.dp)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(player.currentPosition), color = Soft, fontSize = 10.sp)
                            Text(formatTime(player.duration), color = Soft, fontSize = 10.sp)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            IconButton(onClick = onPrevious) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущий трек", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(18.dp))
                            FilledIconButton(
                                onClick = onToggle,
                                modifier = Modifier.size(48.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Lime, contentColor = Night)
                            ) {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Пауза" else "Воспроизвести", modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(18.dp))
                            IconButton(onClick = onNext) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Следующий трек", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }

        // Диалог Эквалайзера
        if (showEqualizerDialog) {
            AlertDialog(
                onDismissRequest = { showEqualizerDialog = false },
                containerColor = Panel,
                title = { Text("Эквалайзер", color = Color.White) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        if (equalizer == null) {
                            Text("Включи трек, чтобы настроить эквалайзер", color = Soft)
                        } else {
                            Text("Системный эквалайзер активен", color = Lime, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            val numBands = equalizer.numberOfBands.toInt()
                            for (i in 0 until numBands) {
                                val freq = equalizer.getCenterFreq(i.toShort()) / 1000
                                var bandLevel by remember { mutableStateOf(equalizer.getBandLevel(i.toShort()).toFloat()) }
                                Text("${freq} Hz: ${bandLevel.toInt()} mB", color = Soft, fontSize = 10.sp)
                                Slider(
                                    value = bandLevel,
                                    onValueChange = { newValue ->
                                        bandLevel = newValue
                                        equalizer.setBandLevel(i.toInt().toShort(), newValue.toInt().toShort())
                                    },
                                    valueRange = equalizer.bandLevelRange[0].toFloat()..equalizer.bandLevelRange[1].toFloat(),
                                    colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showEqualizerDialog = false }) {
                        Text("Закрыть", color = Lime)
                    }
                }
            )
        }

        // Диалог Таймера сна
        if (showSleepTimerDialog) {
            AlertDialog(
                onDismissRequest = { showSleepTimerDialog = false },
                containerColor = Panel,
                title = { Text("Таймер сна", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 15, 30, 45, 60).forEach { mins ->
                            Button(
                                onClick = {
                                    onSetSleepTimer(mins)
                                    showSleepTimerDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = if (sleepTimerMinutes == mins) Lime else Night)
                            ) {
                                Text(if (mins == 0) "Выключить таймер" else "$mins минут", color = if (sleepTimerMinutes == mins) Night else Color.White)
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    if (milliseconds <= 0L || milliseconds == androidx.media3.common.C.TIME_UNSET) return "0:00"
    val totalSeconds = milliseconds / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun AlbumTile(symbol: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF35445A), Color(0xFF252B37)))),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = Lime, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
