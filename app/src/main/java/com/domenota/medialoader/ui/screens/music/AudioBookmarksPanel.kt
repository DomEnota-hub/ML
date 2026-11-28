package com.domenota.medialoader.ui.screens.music

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.playback.*
import com.domenota.medialoader.ui.components.AudioWaveform
import com.domenota.medialoader.ui.components.loadAudioWaveform
import com.domenota.medialoader.ui.components.readCachedAudioWaveform
import com.domenota.medialoader.ui.components.waveformColumnPeaks
import com.domenota.medialoader.ui.theme.musicPlaybackAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The ordinary slider stays accessible. The expanded panel shares the v6 waveform cache. */
@Composable
internal fun AudioBookmarksPanel(
    player: Player, identity: PlaybackDurationIdentity, positionMs: Long, durationMs: Long,
    seekAvailable: Boolean, blocked: Set<String>, permissionEpoch: Long,
    resolve: suspend (MediaItem) -> LibraryMediaRef?,
) {
    val context = LocalContext.current
    val store = remember(context) { AudioBookmarkStore(context) }
    val scope = rememberCoroutineScope()
    val latestResolve by rememberUpdatedState(resolve)
    val latestBlocked by rememberUpdatedState(blocked)
    val privacyEpoch by DuplicatePrivacyGuard.changes.collectAsState()
    val recovery by com.domenota.medialoader.data.storage.HistoricalRecoverySignals.isRunning.collectAsState()
    val accessKey = remember(identity, blocked, privacyEpoch, permissionEpoch, recovery) { Any() }
    var access by remember(accessKey) { mutableStateOf<AudioBookmarkAdmission?>(null) }
    var marks by remember(accessKey) { mutableStateOf<List<AudioBookmark>>(emptyList()) }
    var pending by remember(accessKey) { mutableStateOf<Long?>(null) }
    var name by remember(accessKey) { mutableStateOf("") }
    var busy by remember(accessKey) { mutableStateOf(false) }
    var message by remember(accessKey) { mutableStateOf<String?>(null) }
    var retry by remember(accessKey) { mutableIntStateOf(0) }
    var loadingAccess by remember(accessKey) { mutableStateOf(true) }
    val latestKey by rememberUpdatedState(accessKey)
    fun currentMedia(): MediaItem? = player.currentMediaItem?.takeIf {
        AudioPlaybackMetadata.durationIdentity(it) == identity &&
            !PrivatePlaybackPolicy.blocked(it, false, latestBlocked) &&
            !PrivatePlaybackPolicy.blocked(it, false, DuplicatePrivacyGuard.currentBlockedKeys())
    }
    suspend fun authorize(): AudioBookmarkAdmission {
        val media = currentMedia() ?: error("Трек изменился")
        val epoch = DuplicatePrivacyGuard.generation(context)
        val ref = latestResolve(media) ?: error("Трек недоступен")
        check(currentMedia() != null && accessKey === latestKey)
        return AudioBookmarkAdmission(ref, epoch).also { it.requireCurrent(context) }
    }
    LaunchedEffect(accessKey, retry) {
        if (recovery) { loadingAccess = false; return@LaunchedEffect }
        loadingAccess = true
        try {
            val verified = authorize()
            val loaded = store.list(verified)
            if (currentMedia() != null && accessKey === latestKey) {
                access = verified
                marks = loaded
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            access = null
            marks = emptyList()
        } finally { loadingAccess = false }
    }
    val ready = access != null && !recovery && currentMedia() != null
    fun perform(operation: suspend (AudioBookmarkAdmission) -> List<AudioBookmark>) {
        if (busy || !ready) return
        busy = true
        message = null
        scope.launch {
            try {
                val fresh = authorize()
                check(access?.keys?.any(fresh.keys::contains) == true)
                val changed = operation(fresh)
                if (currentMedia() != null && accessKey === latestKey) {
                    marks = changed
                    access = fresh
                    pending = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (accessKey === latestKey) message = "Не удалось изменить закладки"
            } finally { if (accessKey === latestKey) busy = false }
        }
    }
    fun seek(target: Long) {
        if (!ready || !seekAvailable || currentMedia() == null || busy) return
        busy = true
        message = null
        scope.launch {
            try {
                val fresh = authorize()
                check(access?.keys?.any(fresh.keys::contains) == true)
                check(currentMedia() != null && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                val actual = player.duration.takeIf { it > 0 } ?: durationMs
                AudioBookmarkIdentity.position(target, actual)?.let(player::seekTo)
                    ?: error("Позиция недоступна")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (accessKey === latestKey) message = "Позиция недоступна" }
            finally { if (accessKey === latestKey) busy = false }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (ready) "Закладки (${marks.size})" else "Закладки",
            color = musicPlaybackAccent, style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = {
            val actual = player.duration.takeIf { it > 0 } ?: durationMs
            AudioBookmarkIdentity.position(player.currentPosition, actual)?.let {
                pending = it; name = ""; message = null
            }
        }, enabled = ready && seekAvailable && durationMs > 0 && !busy) {
            Icon(Icons.Rounded.BookmarkAdd, "Добавить закладку")
        }
    }
    if (!ready) {
        Text(if (loadingAccess) "Проверка доступа…" else "Закладки недоступны",
            style = MaterialTheme.typography.bodySmall)
        if (!loadingAccess) TextButton(onClick = { retry++ }) { Text("Повторить") }
    } else {
        val uri = access?.ref?.item?.savedUri
        var peaks by remember(accessKey, uri, durationMs) { mutableStateOf<FloatArray?>(null) }
        var loading by remember(accessKey, uri, durationMs) { mutableStateOf(true) }
        LaunchedEffect(accessKey, uri, durationMs) {
            if (uri == null || durationMs <= 0) { loading = false; return@LaunchedEffect }
            val job = currentCoroutineContext()[Job]
            try {
                authorize()
                val result = withContext(Dispatchers.IO) {
                    val parsed = Uri.parse(uri)
                    val cached=readCachedAudioWaveform(context, parsed, durationMs)
                    com.domenota.medialoader.core.logging.AppLog.d("AudioWaveform","BOOKMARK_WAVEFORM cache="+if(cached!=null)"HIT" else "MISS")
                    val raw=cached ?: loadAudioWaveform(context, parsed, durationMs, isCancelled = { job?.isActive == false })
                    raw.takeIf { it.isNotEmpty() }?.let { waveformColumnPeaks(it, 128) }
                }
                authorize()
                if (currentMedia() != null && accessKey === latestKey) peaks = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { peaks = null }
            finally { loading = false }
        }
        val valid = marks.filter { AudioBookmarkIdentity.position(it.positionMs, durationMs) != null }
        val fractions = valid.map { (it.positionMs.toDouble() / durationMs).toFloat() }
        val wave = peaks
        if (wave != null && durationMs > 0) {
            AudioWaveform(wave, (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f),
                onSeekFraction = { fraction ->
                    val position = (fraction.toDouble() * durationMs).toLong().coerceIn(0, durationMs - 1)
                    val nearest = valid.minByOrNull { kotlin.math.abs(it.positionMs - position) }
                    seek(nearest?.positionMs?.takeIf { kotlin.math.abs(it - position) <= durationMs * 0.025 } ?: position)
                }, accentColor = musicPlaybackAccent, markers = fractions, dragSeek = false)
        } else Text(if (loading) "Загрузка звуковой волны…" else "Звуковая волна недоступна",
            style = MaterialTheme.typography.bodySmall)
        if (marks.isEmpty()) Text("Нет закладок", style = MaterialTheme.typography.bodySmall)
        marks.forEach { mark ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(enabled = mark in valid && seekAvailable && !busy) { seek(mark.positionMs) }
                    .padding(vertical = 8.dp)) {
                    Text(mark.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(bookmarkTime(mark.positionMs), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { perform { store.delete(it, mark.id) } }, enabled = !busy) {
                    Icon(Icons.Rounded.DeleteOutline, "Удалить закладку")
                }
            }
        }
    }
    if (pending == null) message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    val target = pending
    if (target != null && ready) AlertDialog(
        onDismissRequest = { if (!busy) pending = null },
        title = { Text("Добавить закладку") },
        text = {
            Column {
                Text(bookmarkTime(target))
                OutlinedTextField(name, { name = it.take(100) }, Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("Название") }, enabled = !busy)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            val label = name.trim()
            val duration = player.duration.takeIf { it > 0 } ?: durationMs
            perform { store.add(it, label, target, duration) }
        }, enabled = name.isNotBlank() && !busy) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { pending = null }, enabled = !busy) { Text("Отмена") } },
    )
}
private fun bookmarkTime(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}
