package com.domenota.medialoader.ui.screens

import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.Player
import com.domenota.medialoader.data.editor.VideoClipDraft
import com.domenota.medialoader.data.editor.VideoAudioTrackDraft
import com.domenota.medialoader.data.editor.VideoAudioPreviewDeck
import com.domenota.medialoader.data.editor.VideoPreviewQueueSync
import java.util.UUID
import kotlinx.coroutines.delay
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import com.domenota.medialoader.ui.components.AppSlider as Slider
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.editor.CropAspectPreset
import com.domenota.medialoader.data.editor.CropFractions
import com.domenota.medialoader.data.editor.VideoCropMath
import com.domenota.medialoader.data.editor.VideoEditSpec
import com.domenota.medialoader.data.editor.VideoEditor
import com.domenota.medialoader.data.editor.VideoProjectDraft
import com.domenota.medialoader.data.editor.VideoColorGrade
import com.domenota.medialoader.data.editor.VideoExportSettings
import com.domenota.medialoader.data.editor.VideoClipMediaKind
import com.domenota.medialoader.data.editor.VideoTimelineFrameMath
import com.domenota.medialoader.data.editor.VideoFramePosition
import com.domenota.medialoader.data.editor.EditorExportState
import com.domenota.medialoader.ui.components.CropFrameOverlay
import com.domenota.medialoader.ui.components.EditorTextDialog
import com.domenota.medialoader.ui.components.TextEditorVideoSource
import com.domenota.medialoader.data.editor.TextLayer
import com.domenota.medialoader.data.editor.VideoGraphicLayer
import com.domenota.medialoader.data.editor.VideoGraphicKind
import com.domenota.medialoader.data.editor.VideoOverlayTimeline
import com.domenota.medialoader.data.editor.VideoTransitionTiming
import com.domenota.medialoader.data.editor.VideoTransitionDraft
import com.domenota.medialoader.data.editor.VideoSpecialFxDraft
import com.domenota.medialoader.data.editor.TextLayerRenderer
import com.domenota.medialoader.ui.components.MediaTrimControls
import com.domenota.medialoader.ui.components.VideoScrubFrame
import com.domenota.medialoader.ui.components.VideoTimelineStrip
import com.domenota.medialoader.ui.components.rememberEditorExitRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File

private data class VideoInfo(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val frameDurationMs: Long?,
    val isImage: Boolean = false,
)

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen(
    item: DownloadEntity,
    exportState: EditorExportState,
    onBack: () -> Unit,
    onExport: (VideoEditSpec) -> Unit,
    onSaveFrame: (Long, VideoEditSpec) -> Unit,
    onExtractAudio: (Long, Long?) -> Unit,
    onCancelExport: () -> Unit,
) {
    val context = LocalContext.current
    val uri = item.savedUri?.let(Uri::parse)
    val ownExport = exportState.exporting && exportState.sourceId == item.id
    val busyWithOther = exportState.exporting && exportState.sourceId != null &&
        exportState.sourceId != item.id
    var info by remember(uri) { mutableStateOf<VideoInfo?>(null) }
    var removeAudio by remember { mutableStateOf(false) }
    var reverseVideo by remember(item.id) { mutableStateOf(false) }
    var reversePreviewFile by remember(item.id) { mutableStateOf<File?>(null) }
    var reversePreviewBusy by remember(item.id) { mutableStateOf(false) }
    var reversePreviewPercent by remember(item.id) { mutableIntStateOf(0) }
    var reversePreviewError by remember(item.id) { mutableStateOf<String?>(null) }
    var reversePreviewJob by remember(item.id) { mutableStateOf<Job?>(null) }
    val previewScope = rememberCoroutineScope()
    var trimStart by remember { mutableLongStateOf(0L) }
    var trimEnd by remember { mutableLongStateOf(0L) }
    var textLayers by remember(item.id) { mutableStateOf<List<TextLayer>>(emptyList()) }
    var graphicLayers by remember(item.id) {
        mutableStateOf<List<VideoGraphicLayer>>(emptyList())
    }
    var selectedOverlayId by remember(item.id) { mutableStateOf<String?>(null) }
    var selectedTool by remember(item.id) { mutableStateOf<VideoWorkspaceTool?>(null) }
    var referenceMoreOpen by remember(item.id) { mutableStateOf(false) }
    var referenceColorManual by remember(item.id) { mutableStateOf(false) }
    var exportSettings by remember(item.id) { mutableStateOf(VideoExportSettings()) }
    var firstClipInfo by remember(item.id) { mutableStateOf<VideoInfo?>(null) }

    val fullDuration = info?.durationMs ?: 0L
    var project by remember(item.id) { mutableStateOf<VideoProjectDraft?>(null) }
    var selectedClipId by remember(item.id) { mutableStateOf(item.id.toString()) }
    var playheadMs by remember(item.id) { mutableLongStateOf(0L) }
    var timelineIsPlaying by remember(item.id) { mutableStateOf(false) }
    var selectedFrameDurationMs by remember(item.id) { mutableStateOf<Long?>(null) }
    var selectedFrameSourceUri by remember(item.id) { mutableStateOf<String?>(null) }
    var selectedPreviewInfo by remember(item.id) { mutableStateOf<VideoInfo?>(null) }
    var clipError by remember(item.id) { mutableStateOf<String?>(null) }
    var selectedAudioTrackId by remember(item.id) { mutableStateOf<String?>(null) }
    val audioPreviewDeck = remember(item.id) { VideoAudioPreviewDeck(context) }
    DisposableEffect(audioPreviewDeck) { onDispose { audioPreviewDeck.release() } }
    val projectScope = rememberCoroutineScope()
    val currentClip = project?.clips?.firstOrNull { it.id == selectedClipId }
    val crop = currentClip?.crop ?: CropFractions()
    val cropPreset = currentClip?.cropPreset ?: CropAspectPreset.FREE
    val rotation = currentClip?.rotationDegrees ?: 0
    val mirrorHorizontal = currentClip?.mirrorHorizontal ?: false
    val mirrorVertical = currentClip?.mirrorVertical ?: false
    val clipColor = currentClip?.color ?: VideoColorGrade()
    val clipSpecialFx = currentClip?.specialFx ?: VideoSpecialFxDraft()

    fun updateSelectedColor(next: VideoColorGrade) {
        val draft = project ?: return
        project = draft.setColor(selectedClipId, next)
    }

    fun updateSelectedGeometry(
        updatedCrop: CropFractions = crop,
        updatedPreset: CropAspectPreset = cropPreset,
        updatedRotation: Int = rotation,
        updatedMirrorHorizontal: Boolean = mirrorHorizontal,
        updatedMirrorVertical: Boolean = mirrorVertical,
    ) {
        val draft = project ?: return
        project = draft.setGeometry(selectedClipId, updatedCrop, updatedPreset,
            updatedRotation, updatedMirrorHorizontal, updatedMirrorVertical)
    }

    fun applyProject(
        next: VideoProjectDraft,
        selectedId: String = selectedClipId,
        focusSourceMs: Long? = null,
    ) {
        val index = next.indexOf(selectedId)
        require(index >= 0) { "Выбранный клип отсутствует в проекте" }
        val clip = next.clips[index]
        val localMs = clip.outputAt(focusSourceMs ?: clip.inMs)
            .coerceIn(0L, clip.durationMs - 1L)
        project = next
        selectedClipId = selectedId
        // Keep the source frame in view across split, reorder and duplicate.
        playheadMs = next.timelinePosition(index, localMs)
        clipError = null
    }

    val addClips = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        if (selected.isEmpty()) return@rememberLauncherForActivityResult
        projectScope.launch {
            val obtained = selected.mapNotNull { nextUri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(nextUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val metadata = withContext(Dispatchers.IO) { readVisualInfo(context, nextUri) }
                if (metadata != null && metadata.durationMs > 0L) {
                    VideoClipDraft(UUID.randomUUID().toString(), nextUri.toString(),
                        metadata.durationMs, mediaKind = if (metadata.isImage)
                            VideoClipMediaKind.IMAGE else VideoClipMediaKind.VIDEO)
                } else null
            }
            val previous = project
            if (previous != null && obtained.isNotEmpty()) {
                runCatching { previous.addAfter(selectedClipId, obtained) }
                    .onSuccess { applyProject(it, obtained.first().id) }
                    .onFailure { clipError = it.message ?: "Не удалось добавить клипы" }
            } else clipError = "Не удалось прочитать выбранные фото или видео"
        }
    }
    val addAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        projectScope.launch {
            var draft = project ?: return@launch
            var added = 0
            uris.forEach { nextUri ->
                if (draft.audioTracks.size >= VideoProjectDraft.MAX_AUDIO_TRACKS) return@forEach
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        nextUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val audioDuration = withContext(Dispatchers.IO) {
                    readAudioDuration(context, nextUri)
                }
                if (audioDuration != null && audioDuration > 0L) {
                    val track = VideoAudioTrackDraft(
                        id = UUID.randomUUID().toString(),
                        sourceUri = nextUri.toString(),
                        atMs = playheadMs.coerceIn(0L, draft.durationMs - 1L),
                        sourceDurationMs = audioDuration)
                    draft = draft.addAudioTrack(track)
                    selectedAudioTrackId = track.id
                    added++
                }
            }
            if (added > 0) {
                project = draft
                selectedTool = VideoWorkspaceTool.AUDIO
                clipError = null
            } else clipError = "Не удалось добавить аудиофайлы"
        }
    }
    val replaceClip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { nextUri ->
        if (nextUri == null) return@rememberLauncherForActivityResult
        projectScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(nextUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val metadata = withContext(Dispatchers.IO) { readVisualInfo(context, nextUri) }
            val p = project
            if (p != null && metadata != null && metadata.durationMs > 0L) {
                runCatching { p.replace(selectedClipId, nextUri.toString(), metadata.durationMs,
                    if (metadata.isImage) VideoClipMediaKind.IMAGE else VideoClipMediaKind.VIDEO) }
                    .onSuccess { applyProject(it) }
                    .onFailure { clipError = it.message ?: "Не удалось заменить клип" }
            } else clipError = "Не удалось прочитать выбранное фото или видео"
        }
    }
    val pickOverlayImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { picked ->
        val draft = project
        if (picked != null && draft != null && graphicLayers.size < 12) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val from = playheadMs.coerceIn(0L, draft.durationMs - 1L)
            val layer = VideoGraphicLayer(kind = VideoGraphicKind.IMAGE,
                sourceUri = picked.toString(), startMs = from,
                endMs = minOf(draft.durationMs, from + 3000L))
            graphicLayers = graphicLayers + layer
            selectedOverlayId = layer.id
            selectedTool = VideoWorkspaceTool.GRAPHICS
        }
    }
    val hasUnsavedChanges = removeAudio || reverseVideo ||
        textLayers.isNotEmpty() || graphicLayers.isNotEmpty() ||
        project?.audioTracks?.isNotEmpty() == true ||
        (project != null && project!!.clips !=
            listOf(VideoClipDraft(item.id.toString(), uri.toString(), fullDuration.coerceAtLeast(1L))))
    val requestExit = rememberEditorExitRequest(
        hasUnsavedChanges = hasUnsavedChanges && !ownExport,
        onExit = onBack,
    )
    fun resetEdits() {
        removeAudio = false
        reverseVideo = false
        trimStart = 0L
        trimEnd = fullDuration
        project = uri?.takeIf { fullDuration > 0L }?.let {
            VideoProjectDraft(listOf(VideoClipDraft(item.id.toString(),
                it.toString(), fullDuration, mediaKind = if (info?.isImage == true)
                    VideoClipMediaKind.IMAGE else VideoClipMediaKind.VIDEO)))
        }
        selectedClipId = item.id.toString()
        playheadMs = 0L
        textLayers = emptyList()
        graphicLayers = emptyList()
        selectedOverlayId = null
        selectedAudioTrackId = null
        exportSettings = VideoExportSettings()
        audioPreviewDeck.pause()
    }

    fun exportSpec(): VideoEditSpec = VideoEditSpec(
        startMs = 0L,
        endMs = null,
        // Each clip supplies its own geometry during Media3 Composition export.
        removeAudio = removeAudio,
        reverse = reverseVideo,
        textLayers = VideoOverlayTimeline.validatedTexts(
            textLayers, project?.durationMs ?: 0L),
        graphicLayers = VideoOverlayTimeline.validatedGraphics(
            graphicLayers, project?.durationMs ?: 0L),
        project = project,
        exportSettings = exportSettings,
    )

    LaunchedEffect(reverseVideo, project, removeAudio, textLayers, graphicLayers) {
        reversePreviewJob?.cancel()
        reversePreviewJob = null
        reversePreviewFile?.delete()
        reversePreviewFile = null
        reversePreviewPercent = 0
        reversePreviewBusy = false
        reversePreviewError = null
    }
    DisposableEffect(item.id) {
        onDispose {
            reversePreviewJob?.cancel()
            reversePreviewFile?.delete()
        }
    }

    val previewPlayer = remember(uri) {
        uri?.let { sourceUri ->
            ExoPlayer.Builder(context)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(sourceUri))
                    prepare()
                    playWhenReady = false
                }
        }
    }
    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer?.release(); audioPreviewDeck.pause() }
    }
    val previewCrop = if (selectedTool == VideoWorkspaceTool.CROP)
        CropFractions() else crop
    val activeOverlays = project?.let { draft ->
        draft.indexOf(selectedClipId).takeIf { it >= 0 }?.let { index ->
            VideoOverlayTimeline.forClip(draft, index, textLayers, graphicLayers)
        }
    }
    val textPreviewSpec = VideoEditSpec(startMs = currentClip?.inMs ?: 0L,
        cropLeft = previewCrop.left, cropRight = previewCrop.right,
        cropTop = previewCrop.top, cropBottom = previewCrop.bottom,
        rotationDegrees = rotation,
        mirrorHorizontal = mirrorHorizontal, mirrorVertical = mirrorVertical,
        color = clipColor,
        specialFx = clipSpecialFx,
        transitionPhases = project?.let { draft ->
            val index = draft.indexOf(selectedClipId)
            if (index < 0) emptyList()
            else VideoTransitionTiming.forClip(draft, index)
        }.orEmpty(),
        textLayers = activeOverlays?.texts.orEmpty(),
        graphicLayers = activeOverlays?.graphics.orEmpty())
    LaunchedEffect(previewPlayer, selectedClipId, textPreviewSpec) {
        delay(75L) // Coalesce fast slider and drag gestures before rebuilding GL effects.
        val effects = VideoEditor(context).previewEffects(
            textPreviewSpec, timeOffsetMs = textPreviewSpec.startMs)
        previewPlayer?.setVideoEffects(effects)
        previewPlayer?.seekTo(previewPlayer.currentPosition)
    }

    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        info = withContext(Dispatchers.IO) { readVisualInfo(context, uri) }
        trimEnd = info?.durationMs ?: 0L
        info?.takeIf { it.durationMs > 0L }?.let {
            project = VideoProjectDraft(listOf(VideoClipDraft(item.id.toString(),
                uri.toString(), it.durationMs, mediaKind = if (it.isImage)
                    VideoClipMediaKind.IMAGE else VideoClipMediaKind.VIDEO)))
            selectedClipId = item.id.toString()
        }
    }

    val first = project?.clips?.firstOrNull()
    LaunchedEffect(first?.sourceUri, first?.mediaKind) {
        firstClipInfo = first?.let { clip -> withContext(Dispatchers.IO) {
            readVisualInfo(context, Uri.parse(clip.sourceUri))
        } }
    }
    // Image duration participates in queue identity; the player must rebuild when it changes.
    val playlistIdentity = project?.clips?.map {
        listOf(it.sourceUri, it.inMs.toString(), it.outMs.toString(),
            it.mediaKind.name, it.sourceDurationMs.toString())
    }
    LaunchedEffect(previewPlayer, playlistIdentity) {
        val p = project ?: return@LaunchedEffect
        val player = previewPlayer ?: return@LaunchedEffect
        delay(160L) // Debounce trim-handle dragging rather than rebuilding every frame.
        val location = p.locateAt(playheadMs.coerceIn(0L, p.durationMs))
        val items = p.clips.map { clip ->
            MediaItem.Builder().setUri(Uri.parse(clip.sourceUri))
                .apply {
                    if (clip.mediaKind == VideoClipMediaKind.IMAGE) {
                        setImageDurationMs(clip.durationMs)
                        setMediaId("image:${clip.id}:${clip.durationMs}")
                    } else {
                        setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.inMs)
                            .setEndPositionMs(clip.outMs).build())
                    }
                }.build()
        }
        player.pause()
        val clip = p.clips[location.clipIndex]
        val sourceOffset = (clip.sourceAt(location.positionWithinClipMs) - clip.inMs)
            .coerceIn(0L, (clip.sourceSpanMs - 1L).coerceAtLeast(0L))
        player.setMediaItems(items, location.clipIndex, sourceOffset)
        player.prepare()
    }
    LaunchedEffect(previewPlayer, project, removeAudio) {
        val player = previewPlayer ?: return@LaunchedEffect
        val p = project ?: return@LaunchedEffect
        player.volume = if (removeAudio) 0f else 1f
        while (true) {
            // During trim, split or reorder the player still has the old playlist
            // until its debounced rebuild. Never let old positions overwrite the
            // newly selected source frame before the player is synchronized.
            val queueIsCurrent = VideoPreviewQueueSync.matches(
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }, p)
            if (queueIsCurrent) {
                val index = player.currentMediaItemIndex
                if (index in p.clips.indices) {
                    val clip = p.clips[index]
                    if (player.playbackParameters.speed != clip.speed)
                        player.setPlaybackSpeed(clip.speed)
                    val pos = p.timelinePosition(index, clip.outputAt(
                        clip.inMs + player.currentPosition))
                    if (player.isPlaying || pos != playheadMs) playheadMs = pos
                    if (player.isPlaying) selectedClipId = clip.id
                }
            }
            timelineIsPlaying = queueIsCurrent && player.isPlaying
            if (queueIsCurrent) {
                audioPreviewDeck.synchronize(p, playheadMs, player.isPlaying)
            } else audioPreviewDeck.pause()
            delay(75L)
        }
    }
    LaunchedEffect(selectedClipId, currentClip?.sourceUri) {
        selectedFrameDurationMs = null
        selectedFrameSourceUri = null
        selectedPreviewInfo = null
        val clip = project?.clips?.firstOrNull { it.id == selectedClipId }
        if (clip != null) {
            trimStart = clip.inMs
            trimEnd = clip.outMs
            val metadata = withContext(Dispatchers.IO) {
                readVisualInfo(context, Uri.parse(clip.sourceUri))
            }
            selectedFrameDurationMs = metadata?.frameDurationMs
            selectedPreviewInfo = metadata
            selectedFrameSourceUri = clip.sourceUri
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color(0xFF101010),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = requestExit) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть редактор",
                            tint = Color.White)
                    }
                },
                actions = {
                    TextButton(onClick = { onExport(exportSpec()) },
                        enabled = info != null && !ownExport && !busyWithOther &&
                            !reversePreviewBusy) {
                        Text("Сохранить", color = Color.White)
                    }
                    Box {
                        IconButton(onClick = { referenceMoreOpen = true }) {
                            Icon(Icons.Rounded.MoreVert,
                                contentDescription = "Дополнительные действия",
                                tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = referenceMoreOpen,
                            onDismissRequest = { referenceMoreOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Сбросить изменения") },
                                enabled = hasUnsavedChanges && !ownExport && !busyWithOther,
                                onClick = {
                                    referenceMoreOpen = false
                                    resetEdits()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF101010),
                    titleContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        if (uri == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Файл недоступен")
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)
            .background(MaterialTheme.colorScheme.background)) {
            // Only the canvas grows with screen size; trim and tools stay
            // reachable even with portrait video and Android navigation bars.
            // Preview is bounded by the remaining screen height. Portrait videos must
            // never push trim, tool panels or the Android navigation area off-screen.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center) {
                val previewDimensions = selectedPreviewInfo ?: info
                val showEditedFrame = selectedTool != VideoWorkspaceTool.CROP
                val w = ((previewDimensions?.width ?: 16) *
                    if (showEditedFrame) crop.keptWidth else 1f).roundToInt().coerceAtLeast(1)
                val h = ((previewDimensions?.height ?: 9) *
                    if (showEditedFrame) crop.keptHeight else 1f).roundToInt().coerceAtLeast(1)
                val isRotated = rotation % 180 != 0
                val naturalWidth = if (isRotated) h else w
                val naturalHeight = if (isRotated) w else h
                val videoFit = fitVideoPreview(
                    maxWidth.value, maxHeight.value, naturalWidth, naturalHeight)
                CropVideoPreview(
                    modifier = Modifier.width(videoFit.width.dp).height(videoFit.height.dp),
                    player = requireNotNull(previewPlayer),
                    sourceWidth = if (isRotated) h else w,
                    sourceHeight = if (isRotated) w else h,
                    crop = crop,
                    cropPreset = cropPreset,
                    onCropChange = { updateSelectedGeometry(updatedCrop = it) },
                    showCropFrame = selectedTool == VideoWorkspaceTool.CROP,
                    activeOverlayCenter = (textLayers.firstOrNull {
                        it.id == selectedOverlayId
                    }?.let { it.centerX to it.centerY } ?: graphicLayers.firstOrNull {
                        it.id == selectedOverlayId
                    }?.let { it.centerX to it.centerY }).takeIf {
                        selectedTool == VideoWorkspaceTool.TEXT ||
                            selectedTool == VideoWorkspaceTool.GRAPHICS
                    },
                    onOverlayTransform = { dx, dy, zoom, angle ->
                        textLayers = textLayers.map { layer ->
                            if (layer.id == selectedOverlayId) layer.copy(
                                centerX = (layer.centerX + dx).coerceIn(0f, 1f),
                                centerY = (layer.centerY + dy).coerceIn(0f, 1f),
                                size = (layer.size * zoom).coerceIn(.02f, .25f),
                                rotation = normalizeVideoRotation(layer.rotation + angle))
                            else layer
                        }
                        graphicLayers = graphicLayers.map { layer ->
                            if (layer.id == selectedOverlayId) layer.copy(
                                centerX = (layer.centerX + dx).coerceIn(0f, 1f),
                                centerY = (layer.centerY + dy).coerceIn(0f, 1f),
                                size = (layer.size * zoom).coerceIn(.03f, .85f),
                                rotation = normalizeVideoRotation(layer.rotation + angle))
                            else layer
                        }
                    },
                )
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                val activeProject = project
                if (activeProject != null) {
                    VideoMultiClipTimeline(
                        project = activeProject,
                        selectedClipId = selectedClipId,
                        positionMs = playheadMs,
                        isPlaying = timelineIsPlaying,
                        enabled = !ownExport && !busyWithOther,
                        frameIntervalMs = selectedFrameDurationMs,
                        onFrameStep = { direction ->
                            val interval = selectedFrameDurationMs
                            val draft = project
                            if (interval != null && draft != null && draft.indexOf(selectedClipId) >= 0) {
                                val target = VideoTimelineFrameMath.stepSelected(
                                    draft, selectedClipId, playheadMs, direction, interval)
                                val location = draft.locateAt(target)
                                playheadMs = target
                                previewPlayer?.pause()
                                val clip = draft.clips[location.clipIndex]
                                previewPlayer?.seekTo(location.clipIndex,
                                    (clip.sourceAt(location.positionWithinClipMs) - clip.inMs)
                                        .coerceIn(0L, (clip.sourceSpanMs - 1L).coerceAtLeast(0L)))
                            }
                        },
                        onSelect = { id ->
                            if (selectedClipId != id) {
                                val nextIndex = activeProject.indexOf(id)
                                if (nextIndex >= 0) {
                                    val firstMs = activeProject.timelineStart(nextIndex)
                                    playheadMs = firstMs
                                    previewPlayer?.pause()
                                    if (nextIndex < (previewPlayer?.mediaItemCount ?: 0)) {
                                        previewPlayer?.seekTo(nextIndex, 0L)
                                    }
                                }
                                selectedClipId = id
                            }
                            selectedTool = VideoWorkspaceTool.TRIM
                        },
                        onSeek = { target ->
                            val original = activeProject.locateAt(target)
                            val precise = VideoTimelineFrameMath.projectPosition(
                                activeProject, target,
                                selectedFrameDurationMs.takeIf {
                                    original.clipId == selectedClipId &&
                                        selectedFrameSourceUri == activeProject.clips[original.clipIndex].sourceUri
                                })
                            val located = activeProject.locateAt(precise)
                            playheadMs = precise
                            selectedClipId = located.clipId
                            if (previewPlayer != null &&
                                located.clipIndex < previewPlayer.mediaItemCount) {
                                val clip = activeProject.clips[located.clipIndex]
                                previewPlayer.seekTo(located.clipIndex,
                                    (clip.sourceAt(located.positionWithinClipMs) - clip.inMs)
                                        .coerceIn(0L, (clip.sourceSpanMs - 1L).coerceAtLeast(0L)))
                            }
                        },
                        onTrim = { id, from, to ->
                            val prior = project ?: return@VideoMultiClipTimeline
                            val previousClip = prior.clips.firstOrNull { it.id == id }
                                ?: return@VideoMultiClipTimeline
                            val previousIndex = prior.indexOf(id)
                            val fromChanged = previousClip.inMs != from
                            // Track the boundary currently being dragged. Do not silently
                            // keep previewing the old playhead while trim handles move.
                            val boundarySourceMs = if (fromChanged) from else to - 1L
                            val oldSourceOffset = (boundarySourceMs - previousClip.inMs)
                                .coerceIn(0L, (previousClip.sourceSpanMs - 1L).coerceAtLeast(0L))
                            previewPlayer?.pause()
                            if (previousIndex in 0 until (previewPlayer?.mediaItemCount ?: 0)) {
                                previewPlayer?.seekTo(previousIndex, oldSourceOffset)
                            }
                            val oldLocation = prior.locateAt(
                                playheadMs.coerceIn(0L, prior.durationMs - 1L))
                            runCatching { prior.trimClip(id, from, to) }
                                .onSuccess { revised ->
                                    val currentIndex = revised.indexOf(oldLocation.clipId)
                                    if (currentIndex >= 0) {
                                        val currentSegment = revised.clips[currentIndex]
                                        val sourceTime = oldLocation.absoluteSourceMs.coerceIn(
                                            currentSegment.inMs, currentSegment.outMs - 1L)
                                        playheadMs = revised.timelinePosition(
                                            currentIndex, currentSegment.outputAt(sourceTime))
                                    }
                                    project = revised
                                    val editedIndex = revised.indexOf(id)
                                    if (editedIndex >= 0) {
                                        val clip = revised.clips[editedIndex]
                                        val boundedSource = boundarySourceMs.coerceIn(
                                            clip.inMs, (clip.outMs - 1L).coerceAtLeast(clip.inMs))
                                        playheadMs = revised.timelinePosition(
                                            editedIndex, clip.outputAt(boundedSource))
                                    }
                                    if (id == selectedClipId) {
                                        trimStart = from
                                        trimEnd = to
                                    }
                                    clipError = null
                                }
                                .onFailure { clipError = it.message ?: "Недопустимые границы обрезки" }
                        },
                        onMove = { id, newIndex ->
                            val prior = project ?: return@VideoMultiClipTimeline
                            val clip = prior.clips.firstOrNull { it.id == id }
                                ?: return@VideoMultiClipTimeline
                            val oldFocus = prior.locateAt(
                                playheadMs.coerceIn(0L, prior.durationMs - 1L))
                            val focusSourceMs =
                                if (oldFocus.clipId == id) oldFocus.absoluteSourceMs
                                else clip.inMs
                            applyProject(prior.move(id, newIndex), id, focusSourceMs)
                        },
                        onAdd = { addClips.launch(arrayOf("video/*", "image/*")) },
                        onSelectTransition = { id ->
                            val draft = project ?: return@VideoMultiClipTimeline
                            val index = draft.indexOf(id)
                            if (index in 0 until draft.clips.lastIndex) {
                                val clip = draft.clips[index]
                                selectedClipId = id
                                selectedTool = VideoWorkspaceTool.TRANSITION
                                previewPlayer?.pause()
                                val local = (clip.durationMs -
                                    minOf(clip.durationMs / 4L, 400L)).coerceAtLeast(0L)
                                playheadMs = draft.timelinePosition(index, local)
                                previewPlayer?.seekTo(index,
                                    (clip.sourceAt(local) - clip.inMs)
                                        .coerceIn(0L, (clip.sourceSpanMs - 1L).coerceAtLeast(0L)))
                            }
                        },
                        onTogglePlayback = {
                            previewPlayer?.let {
                                if (it.isPlaying) it.pause()
                                else {
                                    if (playheadMs >= activeProject.durationMs - 40L) {
                                        playheadMs = 0L
                                        it.seekTo(0, 0L)
                                    }
                                    it.play()
                                }
                            }
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Клипов: ${activeProject.clips.size}",
                            style = MaterialTheme.typography.labelSmall)
                        Text("Выбран: ${activeProject.indexOf(selectedClipId) + 1}",
                            style = MaterialTheme.typography.labelSmall)
                    }
                } else Text("Читаем видео…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                clipError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (selectedTool != null) {
                Column(Modifier.fillMaxWidth().heightIn(max = 218.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                    when (selectedTool) {
                        VideoWorkspaceTool.TRIM -> {
                            val current = project
                            val index = current?.indexOf(selectedClipId) ?: -1
                            val selected = current?.clips?.getOrNull(index)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { addClips.launch(arrayOf("video/*", "image/*")) }) {
                                    Text("+ Добавить")
                                }
                                TextButton(onClick = {
                                    if (current != null && selected != null) {
                                        val local = (playheadMs - current.timelineStart(index))
                                            .coerceIn(0L, selected.durationMs)
                                        val point = selected.sourceAt(local)
                                        val rightId = UUID.randomUUID().toString()
                                        runCatching {
                                            current.splitAt(selected.id, point, rightId)
                                        }.onSuccess { applyProject(it, rightId, point) }
                                            .onFailure { clipError = "Раздели клип не ближе 0,1 с к краю" }
                                    }
                                }, enabled = selected != null &&
                                    selected.mediaKind == VideoClipMediaKind.VIDEO &&
                                    selected.sourceSpanMs >= 200L) {
                                    Text("Разделить")
                                }
                                TextButton(onClick = {
                                    if (current != null && selected != null) {
                                        val copyId = UUID.randomUUID().toString()
                                        runCatching {
                                            current.duplicate(selected.id, copyId)
                                        }.onSuccess { applyProject(it, copyId, selected.inMs) }
                                            .onFailure { clipError = it.message }
                                    }
                                }, enabled = selected != null) { Text("Копировать") }
                                TextButton(onClick = { replaceClip.launch(arrayOf("video/*", "image/*")) },
                                    enabled = selected != null) { Text("Заменить") }
                                TextButton(onClick = {
                                    if (current != null && selected != null && current.clips.size > 1) {
                                        val next = current.delete(selected.id)
                                        applyProject(next, next.clips[index.coerceAtMost(
                                            next.clips.lastIndex)].id)
                                    }
                                }, enabled = selected != null && (current?.clips?.size ?: 0) > 1) {
                                    Text("Удалить")
                                }
                                TextButton(onClick = {
                                    if (current != null && index > 0)
                                        applyProject(current.move(selectedClipId, index - 1))
                                }, enabled = index > 0) { Text("←") }
                                TextButton(onClick = {
                                    if (current != null && index in 0 until current.clips.lastIndex)
                                        applyProject(current.move(selectedClipId, index + 1))
                                }, enabled = current != null && index >= 0 &&
                                    index < current.clips.lastIndex) { Text("→") }
                            }
                            Text("Жёлтые края — обрезка. Удержание и сдвиг клипа — изменение порядка. " +
                                "Свайп по таймлайну — прокрутка.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        VideoWorkspaceTool.SPEED -> {
                            val selected = currentClip
                            Text(if (selected?.mediaKind == VideoClipMediaKind.IMAGE)
                                "Длительность фотографии" else "Скорость выбранного клипа",
                                style = MaterialTheme.typography.titleMedium)
                            if (selected?.mediaKind == VideoClipMediaKind.IMAGE) {
                                Text("Фото: " + "%.1f".format(java.util.Locale.US,
                                    selected.durationMs / 1000f) + " с",
                                    style = MaterialTheme.typography.bodyMedium)
                                Slider(value = selected.durationMs.toFloat(),
                                    onValueChange = { next ->
                                        val p = project ?: return@Slider
                                        val duration = (next / 500f).toInt()
                                            .coerceIn(1, 60) * 500L
                                        if (duration != selected.durationMs) {
                                            previewPlayer?.pause()
                                            applyProject(p.setPhotoDuration(selected.id, duration),
                                                selected.id, 0L)
                                        }
                                    }, valueRange = 500f..30000f, steps = 58)
                                Text("Фотография не имеет собственного FPS и звука; " +
                                    "Media3 формирует статичные кадры на время показа.",
                                    style = MaterialTheme.typography.bodySmall)
                            } else if (selected != null) {
                            Text("Длительность на шкале пересчитывается автоматически; " +
                                "звук меняет скорость вместе с видео.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("×" + "%.2f".format(java.util.Locale.US, selected.speed) +
                                    " · " + "%.1f".format(java.util.Locale.US,
                                        selected.durationMs / 1000f) + " с",
                                    style = MaterialTheme.typography.titleMedium)
                                Slider(value = selected.speed,
                                    onValueChange = { newSpeed ->
                                        val existing = project ?: return@Slider
                                        val focus = existing.locateAt(
                                            playheadMs.coerceIn(0L, existing.durationMs - 1L))
                                        val sourceMs = if (focus.clipId == selected.id)
                                            focus.absoluteSourceMs else selected.inMs
                                        val rounded = (kotlin.math.round(newSpeed * 4f) / 4f)
                                            .coerceIn(VideoClipDraft.MIN_SPEED, VideoClipDraft.MAX_SPEED)
                                        if (rounded != selected.speed) {
                                            previewPlayer?.pause()
                                            applyProject(existing.setSpeed(selected.id, rounded),
                                                selected.id, sourceMs)
                                        }
                                    },
                                    valueRange = VideoClipDraft.MIN_SPEED..VideoClipDraft.MAX_SPEED,
                                    steps = 14,
                                )
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f, 4f).forEach { value ->
                                        FilterChip(
                                            selected = selected.speed == value,
                                            onClick = {
                                                val existing = project ?: return@FilterChip
                                                val focus = existing.locateAt(
                                                    playheadMs.coerceIn(0L, existing.durationMs - 1L))
                                                previewPlayer?.pause()
                                                applyProject(existing.setSpeed(selected.id, value),
                                                    selected.id, if (focus.clipId == selected.id)
                                                        focus.absoluteSourceMs else selected.inMs)
                                            },
                                            label = { Text("×" + value.toString()) })
                                    }
                                }
                            }
                        }
                        VideoWorkspaceTool.CROP -> {
            Text("Кадрирование", style = MaterialTheme.typography.titleMedium)
            Text(
                "Потяни сторону или угол рамки. Затемнённая область будет удалена.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CropAspectPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = cropPreset == preset,
                        onClick = {
                            val dimensions = selectedPreviewInfo ?: info
                            val updatedCrop =
                                if (dimensions != null && preset != CropAspectPreset.FREE)
                                    VideoCropMath.centerCrop(dimensions.width, dimensions.height, preset)
                                else if (preset == CropAspectPreset.FREE) crop else CropFractions()
                            updateSelectedGeometry(updatedPreset = preset, updatedCrop = updatedCrop)
                        },
                        label = { Text(preset.label) },
                    )
                }
            }

            val dimensions = selectedPreviewInfo ?: info
            if (dimensions != null) {
                Spacer(Modifier.height(10.dp))
                val outputWidth = (dimensions.width * crop.keptWidth).roundToInt().coerceAtLeast(1)
                val outputHeight = (dimensions.height * crop.keptHeight).roundToInt().coerceAtLeast(1)
                val rotated = rotation % 180 != 0
                Text(
                    "Итог: ${if (rotated) outputHeight else outputWidth} × " +
                        "${if (rotated) outputWidth else outputHeight} px",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }


                        }
                        VideoWorkspaceTool.ROTATE -> {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Поворот", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (rotation == 0) "Без поворота" else "${rotation}°",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    updateSelectedGeometry(updatedRotation = (rotation + 90) % 360)
                }) {
                    Icon(Icons.Rounded.RotateRight, contentDescription = null)
                    Text("  Повернуть")
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mirrorHorizontal,
                    onClick = { updateSelectedGeometry(updatedMirrorHorizontal = !mirrorHorizontal) },
                    label = { Text("Отразить ↔") },
                )
                FilterChip(
                    selected = mirrorVertical,
                    onClick = { updateSelectedGeometry(updatedMirrorVertical = !mirrorVertical) },
                    label = { Text("Отразить ↕") },
                )
            }
            Text("Поворот и отражение применяются к выбранному клипу.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

                        }
                        VideoWorkspaceTool.COLOR -> {
                            VideoColorPanel(
                                manualByDefault = referenceColorManual,
                                clip = currentClip,
                                color = clipColor,
                                enabled = currentClip != null && !ownExport && !busyWithOther,
                                onColorChange = ::updateSelectedColor,
                            )
                        }
                        VideoWorkspaceTool.TRANSITION, VideoWorkspaceTool.EFFECTS -> {
                            VideoStage7EffectsPanel(
                                project = project,
                                selectedClipId = selectedClipId,
                                transitionTool = selectedTool == VideoWorkspaceTool.TRANSITION,
                                enabled = !ownExport && !busyWithOther,
                                onTransition = { transition ->
                                    val draft = project
                                    if (draft != null) {
                                        runCatching {
                                            draft.setTransitionAfter(selectedClipId, transition)
                                        }.onSuccess { project = it; clipError = null }
                                            .onFailure {
                                                clipError = it.message ?: "Переход не применён"
                                            }
                                    }
                                },
                                onSpecialFx = { effect ->
                                    val draft = project
                                    if (draft != null) {
                                        runCatching {
                                            draft.setSpecialFx(selectedClipId, effect)
                                        }.onSuccess { project = it; clipError = null }
                                            .onFailure {
                                                clipError = it.message ?: "Эффект не применён"
                                            }
                                    }
                                },
                            )
                        }
                        VideoWorkspaceTool.EXPORT -> {
                            val base = project?.clips?.firstOrNull()
                            VideoExportPanel(
                                settings = exportSettings,
                                sourceWidth = firstClipInfo?.width,
                                sourceHeight = firstClipInfo?.height,
                                cropWidth = base?.crop?.keptWidth ?: 1f,
                                cropHeight = base?.crop?.keptHeight ?: 1f,
                                rotationDegrees = base?.rotationDegrees ?: 0,
                                estimatedFrameRate = firstClipInfo?.frameDurationMs
                                    ?.takeIf { it > 0 }?.let { (1000L / it).toInt() } ?: 30,
                                reverseEnabled = reverseVideo,
                                enabled = !ownExport && !busyWithOther,
                                onSettingsChange = { exportSettings = it },
                            )
                        }
                        VideoWorkspaceTool.AUDIO -> {
                            VideoAudioTracksPanel(
                                project = project,
                                selectedTrackId = selectedAudioTrackId,
                                removeOriginalAudio = removeAudio,
                                enabled = !ownExport && !busyWithOther,
                                onRemoveOriginalAudio = { removeAudio = it },
                                onAdd = { addAudio.launch(arrayOf("audio/*")) },
                                onSelect = { selectedAudioTrackId = it },
                                onUpdate = { id, transform ->
                                    val draft = project ?: return@VideoAudioTracksPanel
                                    runCatching { draft.editAudioTrack(id, transform) }
                                        .onSuccess { project = it; clipError = null }
                                        .onFailure { clipError = it.message ?: "Ошибка настройки аудио" }
                                },
                                onDelete = { id ->
                                    val draft = project ?: return@VideoAudioTracksPanel
                                    project = draft.removeAudioTrack(id)
                                    selectedAudioTrackId = project?.audioTracks?.firstOrNull()?.id
                                },
                                onJumpTo = { next ->
                                    val draft = project ?: return@VideoAudioTracksPanel
                                    val position = next.coerceIn(0L, draft.durationMs - 1L)
                                    val location = draft.locateAt(position)
                                    val clip = draft.clips[location.clipIndex]
                                    playheadMs = position
                                    previewPlayer?.pause()
                                    previewPlayer?.seekTo(location.clipIndex,
                                        (clip.sourceAt(location.positionWithinClipMs) - clip.inMs)
                                            .coerceIn(0L, clip.sourceSpanMs - 1L))
                                },
                            )
                        }
                        VideoWorkspaceTool.REVERSE -> {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FastRewind, contentDescription = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Обратное воспроизведение", style = MaterialTheme.typography.titleMedium)
                        Text("Обратное воспроизведение итогового проекта. " +
                            "Звук тоже обращается, если не отключён.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = reverseVideo, onCheckedChange = { reverseVideo = it })
            }
            if (reverseVideo) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (reversePreviewFile == null) {
                        previewPlayer?.pause()
                        reversePreviewJob = previewScope.launch {
                            reversePreviewBusy = true
                            reversePreviewError = null
                            reversePreviewPercent = 0
                            try {
                                val file = VideoEditor(context).export(uri, exportSpec()) {
                                    reversePreviewPercent = it
                                }
                                currentCoroutineContext().ensureActive()
                                reversePreviewFile = file
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                reversePreviewError = error.message ?: "Не удалось создать предпросмотр реверса"
                            } finally {
                                reversePreviewBusy = false
                            }
                        }
                        }
                    },
                    enabled = info != null && !reversePreviewBusy && !ownExport && !busyWithOther,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.FastRewind, contentDescription = null)
                    Text("  Посмотреть в обратном порядке")
                }
                if (reversePreviewBusy) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { reversePreviewPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { reversePreviewJob?.cancel() }) { Text("Отменить предпросмотр") }
                }
                reversePreviewError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }

                        }
                        VideoWorkspaceTool.TEXT, VideoWorkspaceTool.GRAPHICS -> {
                            val draft = project
                            val graphicsTab = selectedTool == VideoWorkspaceTool.GRAPHICS
                            VideoOverlayPanel(
                                graphicsTab = graphicsTab,
                                durationMs = draft?.durationMs ?: 0L,
                                playheadMs = playheadMs,
                                texts = textLayers,
                                graphics = graphicLayers,
                                selectedId = selectedOverlayId,
                                enabled = draft != null && !ownExport && !busyWithOther,
                                onSelect = { selectedOverlayId = it },
                                onAddText = {
                                    if (draft != null && textLayers.size < 8) {
                                        val from = playheadMs.coerceIn(0L, draft.durationMs - 1L)
                                        val added = TextLayer(startMs = from,
                                            endMs = minOf(draft.durationMs, from + 3000L))
                                        textLayers = textLayers + added
                                        selectedOverlayId = added.id
                                        previewPlayer?.pause()
                                    }
                                },
                                onAddGraphic = { kind ->
                                    if (draft != null && graphicLayers.size < 12) {
                                        val from = playheadMs.coerceIn(0L, draft.durationMs - 1L)
                                        val added = VideoGraphicLayer(kind = kind, startMs = from,
                                            endMs = minOf(draft.durationMs, from + 3000L))
                                        graphicLayers = graphicLayers + added
                                        selectedOverlayId = added.id
                                        previewPlayer?.pause()
                                    }
                                },
                                onAddImage = { pickOverlayImage.launch(arrayOf("image/*")) },
                                onChangeText = { next ->
                                    textLayers = textLayers.map {
                                        if (it.id == next.id) next else it
                                    }
                                },
                                onChangeGraphic = { next ->
                                    graphicLayers = graphicLayers.map {
                                        if (it.id == next.id) next else it
                                    }
                                },
                                onRemoveText = { id ->
                                    textLayers = textLayers.filterNot { it.id == id }
                                    selectedOverlayId = null
                                },
                                onRemoveGraphic = { id ->
                                    graphicLayers = graphicLayers.filterNot { it.id == id }
                                    selectedOverlayId = null
                                },
                                onSeek = { next ->
                                    if (draft != null) {
                                        val where = draft.locateAt(next)
                                        val clip = draft.clips[where.clipIndex]
                                        playheadMs = next
                                        previewPlayer?.pause()
                                        previewPlayer?.seekTo(where.clipIndex,
                                            (where.absoluteSourceMs - clip.inMs)
                                                .coerceIn(0L, clip.sourceSpanMs - 1L))
                                    }
                                },
                            )
                        }
                        VideoWorkspaceTool.MORE -> {
            if (!ownExport) {
                if (busyWithOther) {
                    Text(
                        "Сейчас обрабатывается другой файл. Дождись завершения или отмени обработку из уведомления.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        previewPlayer?.pause()
                        val positionMs = VideoFramePosition.clamp(
                            trimStart + (previewPlayer?.currentPosition ?: 0L),
                            trimStart, trimEnd, fullDuration,
                        )
                        onSaveFrame(positionMs, VideoEditSpec(
                            startMs = trimStart,
                            cropLeft = crop.left,
                            cropRight = crop.right,
                            cropTop = crop.top,
                            cropBottom = crop.bottom,
                            rotationDegrees = rotation,
                            textLayers = textLayers.mapNotNull { it.within(trimEnd - trimStart) },
                        ))
                    },
                    enabled = info != null && !busyWithOther &&
                        currentClip?.sourceUri == uri.toString(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
                    Text("  Сохранить кадр")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        onExtractAudio(
                            trimStart,
                            trimEnd.takeIf { end ->
                                end > 0 && end < (info?.durationMs ?: Long.MAX_VALUE)
                            },
                        )
                    },
                    enabled = info != null && !busyWithOther &&
                        currentClip?.sourceUri == uri.toString(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Rounded.Audiotrack, contentDescription = null)
                    Text("  Извлечь аудио")
                }
            }
            exportState.message
                ?.takeIf { exportState.sourceId == null || exportState.sourceId == item.id }
                ?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                        }
                        null -> Unit
                    }
                }
            }
            exportState.outputDetails
                ?.takeIf { exportState.sourceId == item.id && !exportState.exporting }
                ?.let { details ->
                    Text("Проверенный файл: " + details,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            VideoWorkspaceToolbar(
                selected = selectedTool,
                onSelect = { next ->
                    selectedTool = next
                    if (next == VideoWorkspaceTool.TEXT) previewPlayer?.pause()
                },
                enabled = info != null && !ownExport && !busyWithOther,
                onColorManualMode = { referenceColorManual = it },
                colorManualMode = referenceColorManual,
            )
        }
    }
    if (ownExport) {
        VideoV8SaveProgress(
            operation = exportState.operation,
            progress = exportState.progress,
            onCancel = onCancelExport,
        )
    }
    reversePreviewFile?.let { file ->
        ReverseVideoPreviewDialog(file) {
            reversePreviewFile = null
            file.delete()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ReverseVideoPreviewDialog(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Предпросмотр реверса") },
        text = {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun CropVideoPreview(
    modifier: Modifier = Modifier,
    player: ExoPlayer,
    sourceWidth: Int,
    sourceHeight: Int,
    crop: CropFractions,
    cropPreset: CropAspectPreset,
    onCropChange: (CropFractions) -> Unit,
    showCropFrame: Boolean = true,
    activeOverlayCenter: Pair<Float, Float>? = null,
    onOverlayTransform: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
) {
    val latestOverlayTransform by rememberUpdatedState(onOverlayTransform)
    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    isClickable = false
                    isFocusable = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        if (showCropFrame) CropFrameOverlay(
            crop = crop,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            preset = cropPreset,
            onCropChange = onCropChange,
            modifier = Modifier.fillMaxSize(),
        )
        if (activeOverlayCenter != null && !showCropFrame) {
            Canvas(Modifier.fillMaxSize().pointerInput(activeOverlayCenter) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    latestOverlayTransform(
                        pan.x / size.width.coerceAtLeast(1),
                        pan.y / size.height.coerceAtLeast(1),
                        zoom,
                        rotation)
                }
            }) {
                val center = Offset(size.width * activeOverlayCenter.first,
                    size.height * activeOverlayCenter.second)
                val radius = 17.dp.toPx()
                drawCircle(
                    Color(0xFFFFC629), radius = radius,
                    center = center, style = Stroke(2.dp.toPx()))
                drawLine(Color.White, center - Offset(radius * .55f, 0f),
                    center + Offset(radius * .55f, 0f), 1.6.dp.toPx())
                drawLine(Color.White, center - Offset(0f, radius * .55f),
                    center + Offset(0f, radius * .55f), 1.6.dp.toPx())
            }
        }
    }
}

private fun readVideoInfo(context: android.content.Context, uri: Uri): VideoInfo? =
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return@runCatching null
            val encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return@runCatching null
            val sourceRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val (width, height) = if (sourceRotation % 180 != 0) {
                encodedHeight to encodedWidth
            } else {
                encodedWidth to encodedHeight
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val captureFrameRate =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toDoubleOrNull()
                    ?.takeIf { it in 1.0..240.0 }
            val frameCount =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
            val frameDurationMs = when {
                captureFrameRate != null -> (1_000.0 / captureFrameRate).toLong().coerceAtLeast(1L)
                frameCount != null && duration > 0L ->
                    (duration.toDouble() / frameCount).toLong().coerceAtLeast(1L)
                else -> null
            }
            VideoInfo(width, height, duration, frameDurationMs)
        } finally {
            retriever.release()
        }
    }.getOrNull()


private fun readAudioDuration(context: android.content.Context, uri: Uri): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.takeIf { it > 0L }
    } catch (_: Exception) {
        null
    } finally { retriever.release() }
}

/** Still images do not carry a video duration; default to a 3-second timeline clip. */
private fun readVisualInfo(context: android.content.Context, uri: Uri): VideoInfo? {
    readVideoInfo(context, uri)?.let { if (it.durationMs > 0L) return it }
    return runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        input.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) null
        else VideoInfo(options.outWidth, options.outHeight, 3000L, null, isImage = true)
    }.getOrNull()
}

/** Match text/sticker renderer rotation limits after continuous two-finger gestures. */
internal fun normalizeVideoRotation(degrees: Float): Float {
    if (!degrees.isFinite()) return 0f
    return ((degrees + 180f) % 360f + 360f) % 360f - 180f
}
