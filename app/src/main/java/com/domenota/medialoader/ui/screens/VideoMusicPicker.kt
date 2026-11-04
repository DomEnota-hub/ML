package com.domenota.medialoader.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

internal data class VideoMusicChoice(val id: String, val uri: String, val name: String, val durationMs: Long)

/** HTML v8's actual capabilities: local imports, audition, add and remove from the list. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoMusicPicker(choices: List<VideoMusicChoice>, canAdd: Boolean,
    errorMessage: String? = null, importing: Boolean = false,
    onImport: () -> Unit, onChoose: (VideoMusicChoice) -> Unit,
    onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var localTab by remember { mutableStateOf(true) }
    var previewId by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var failed by remember { mutableStateOf(false) }
    val player = remember { ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA).build(), false)
        setHandleAudioBecomingNoisy(true)
    } }
    val audioManager = remember(context) { context.getSystemService(android.media.AudioManager::class.java) }
    val focus = remember(player) {
        android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change -> if (change < 0) player.pause() },
                android.os.Handler(android.os.Looper.getMainLooper())).build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                failed = true; audioManager.abandonAudioFocusRequest(focus)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) audioManager.abandonAudioFocusRequest(focus)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release(); audioManager.abandonAudioFocusRequest(focus) }
    }
    LaunchedEffect(player, previewId, playing) {
        position = player.currentPosition
        while (playing) { delay(250); position = player.currentPosition }
    }
    fun time(ms: Long) = "%d:%02d".format(java.util.Locale.US, ms / 60_000, ms / 1000 % 60)
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let {
                androidx.core.view.WindowInsetsControllerCompat(it, view).isAppearanceLightStatusBars = false
            }
        }
        Column(Modifier.fillMaxSize().background(Color(0xFF101010)).systemBarsPadding()
            .padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад из музыки", tint = Color.White) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("Выберите музыку", color = Color.White, fontSize = 15.sp)
                }
                Spacer(Modifier.width(48.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(false to "Онлайн", true to "На устройстве").forEach { (local, label) ->
                    Surface(onClick = { localTab = local; player.pause(); audioManager.abandonAudioFocusRequest(focus) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (localTab == local) Color(0xFF2C2C2C) else Color.Transparent,
                        modifier = Modifier.weight(1f).height(46.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(label, color = Color.LightGray, fontSize = 13.sp) }
                    }
                }
            }
            if (!localTab) {
                Text("Музыка онлайн пока недоступна", color = Color(0xFFAAAAAA), fontSize = 13.sp)
            } else {
                Surface(onClick = onImport, enabled = !importing, shape = RoundedCornerShape(16.dp), color = Color(0xFF2C2C2C),
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(VideoV8Icons.musicImport, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp)); Text(if (importing) "Читаем аудио…" else "Импорт", color = Color.White, fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (errorMessage != null) Text(errorMessage, color = Color(0xFFFF8D8D), fontSize = 12.sp)
                if (failed) Text("Не удалось прослушать аудио", color = Color(0xFFFF8D8D), fontSize = 12.sp)
                if (choices.isEmpty()) Text("Импортируйте аудио с устройства", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(choices, key = { _, item -> item.id }) { index, choice ->
                        Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val selected = previewId == choice.id && playing
                            Surface(onClick = {
                                failed = false
                                if (previewId == choice.id && playing) {
                                    player.pause(); audioManager.abandonAudioFocusRequest(focus)
                                } else if (audioManager.requestAudioFocus(focus) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                                    if (previewId != choice.id) {
                                        previewId = choice.id
                                        player.setMediaItem(MediaItem.fromUri(Uri.parse(choice.uri))); player.prepare()
                                    } else if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                                    player.play()
                                } else failed = true
                            }, shape = RoundedCornerShape(12.dp),
                                color = listOf(Color(0xFFA8C7CB), Color(0xFFD6B2BD), Color(0xFFCFBF9A))[index % 3],
                                modifier = Modifier.size(64.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(if (selected) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        if (selected) "Остановить ${choice.name}" else "Прослушать ${choice.name}",
                                        tint = Color.White, modifier = Modifier.size(25.dp))
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(choice.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${time(if (previewId == choice.id) position else 0)}/${time(choice.durationMs)}",
                                    color = Color.LightGray, fontSize = 11.sp)
                            }
                            IconButton(onClick = {
                                if (previewId == choice.id) { player.stop(); previewId = null; audioManager.abandonAudioFocusRequest(focus) }
                                onRemove(choice.id)
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(VideoV8Icons.trash, "Убрать ${choice.name} из списка", tint = Color.LightGray, modifier = Modifier.size(21.dp))
                            }
                            TextButton(onClick = { player.pause(); audioManager.abandonAudioFocusRequest(focus); onChoose(choice) }, enabled = canAdd,
                                modifier = Modifier.size(32.dp), contentPadding = PaddingValues(0.dp)) {
                                Text("+", fontSize = 27.sp, color = videoToolYellow)
                            }
                        }
                    }
                }
            }
        }
    }
}
