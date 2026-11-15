package com.domenota.medialoader.ui.model

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType
import java.util.Locale

/** Display only. Never pass these names to storage, matching, sorting or rename. */
internal object FileNameDisplay {
    const val KEY = "hide_file_extensions"
    private val photo = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif", "bmp", "tiff", "tif", "dng")
    private val video = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "3g2", "m4v", "mpeg", "mpg", "ts", "mts", "m2ts", "wmv", "flv")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma", "amr", "aiff", "aif", "alac", "mka", "mp4", "webm", "3gp", "oga")

    fun read(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, false)
    fun write(prefs: SharedPreferences, enabled: Boolean) { prefs.edit().putBoolean(KEY, enabled).apply() }

    fun name(value: String, kind: MediaKind, hide: Boolean): String {
        if (!hide || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) return value
        val dot = value.lastIndexOf('.')
        if (dot <= 0 || dot == value.lastIndex) return value
        val extension = value.substring(dot + 1).lowercase(Locale.ROOT)
        val known = when (kind) { MediaKind.IMAGE -> photo; MediaKind.VIDEO -> video; MediaKind.AUDIO -> audio }
        val stem = value.substring(0, dot)
        return if (extension in known && stem.isNotBlank() && stem.any { it != '.' }) stem else value
    }

    fun kind(type: MediaType): MediaKind = when(type) {
        MediaType.PHOTO -> MediaKind.IMAGE
        MediaType.VIDEO -> MediaKind.VIDEO
        MediaType.AUDIO -> MediaKind.AUDIO
    }
    fun name(row: DownloadEntity, hide: Boolean): String = name(row.originalName, kind(row.mediaType), hide)
    fun audioTitle(row: DownloadEntity, hide: Boolean): String =
        row.recognizedTitle ?: row.sourceAudioTitle ?: name(row, hide)
}

internal data class FileNameDisplayPreference(val hide: Boolean = false, val setHide: (Boolean) -> Unit = {})
internal val LocalFileNameDisplay = compositionLocalOf { FileNameDisplayPreference() }

/** One observed preference for the whole app, including nested dialogs and library themes. */
@Composable
internal fun FileNameDisplayProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ui", 0) }
    var hide by remember(prefs) { mutableStateOf(FileNameDisplay.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == FileNameDisplay.KEY || key == null) hide = FileNameDisplay.read(prefs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    CompositionLocalProvider(LocalFileNameDisplay provides FileNameDisplayPreference(hide) { value ->
        FileNameDisplay.write(prefs, value)
        hide = value
    }, content = content)
}

@Composable internal fun displayFileName(name: String, kind: MediaKind): String =
    FileNameDisplay.name(name, kind, LocalFileNameDisplay.current.hide)
@Composable internal fun displayFileName(row: DownloadEntity): String =
    FileNameDisplay.name(row, LocalFileNameDisplay.current.hide)
@Composable internal fun displayAudioTitle(row: DownloadEntity): String =
    FileNameDisplay.audioTitle(row, LocalFileNameDisplay.current.hide)
