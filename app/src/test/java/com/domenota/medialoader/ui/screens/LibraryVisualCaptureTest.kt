package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.data.playback.EqualizerSettings
import com.domenota.medialoader.ui.components.*
import com.domenota.medialoader.ui.screens.gallery.GalleryMediaViewer
import com.domenota.medialoader.ui.screens.music.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Native Compose captures for human comparison to HTML. No pixel-golden implementation tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryVisualCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun captureNativeScreensInBothThemes() {
        val output = File("/tmp/medialoader-native-a").apply { mkdirs() }
        val photoFile = File("/tmp/medialoader-visual-fixture.jpg")
        if (!photoFile.exists()) {
            val image = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(65, 87, 110)) }
            photoFile.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }; image.recycle()
        }
        val photos = (0..31).map { n -> DownloadEntity("photo$n", "device", "Фото $n.jpg", MediaType.PHOTO,
            DownloadState.COMPLETED, Uri.fromFile(photoFile).toString(), 123456L, 1793203200000L - (n / 16) * 86400000L) }
        val tracks = (0..9).map { n -> photos[n].copy(id = "audio$n", mediaType = MediaType.AUDIO,
            savedUri = "file:///track$n.mp3", originalName = "Track $n.mp3", recognizedTitle = listOf("Последнее в мире фото", "Хватит, довольно", "I'm Good (Blue)")[n % 3],
            recognizedArtist = listOf("TEMNEE & TRIDA", "Sveta", "David Guetta & Bebe Rexha")[n % 3], previewUrl = Uri.fromFile(photoFile).toString(), durationMs = 175000) }
        val folders = listOf(CatalogFolderEntity("folder", "Путешествия", "путешествия", 0))
        val assignment = CatalogAssignmentEntity("photo0", "folder", true)
        val playlists = listOf(MediaPlaylist("p", "В дороге", tracks.take(8).map { it.id }))
        // Offline fixtures use the same persisted artwork cache as downloaded music.
        tracks.forEach { track ->
            val cover = com.domenota.medialoader.core.storage.AudioThumbnailInvalidation.file(compose.activity.filesDir, track.savedUri!!)
            cover.parentFile!!.mkdirs(); photoFile.copyTo(cover, overwrite = true)
        }
        kotlinx.coroutines.runBlocking {
            com.domenota.medialoader.ui.components.prepareDownloadThumbnail(compose.activity, photos.first().savedUri!!, com.domenota.medialoader.ui.model.MediaKind.IMAGE)
            tracks.forEach { com.domenota.medialoader.ui.components.prepareDownloadThumbnail(compose.activity, it.savedUri!!, com.domenota.medialoader.ui.model.MediaKind.AUDIO) }
        }
        var screen by mutableStateOf("gallery")
        var dark by mutableStateOf(true)
        val player = ExoPlayer.Builder(compose.activity).build()
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { LibraryTheme {
                Surface(Modifier.fillMaxWidth().height(740.dp), color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground) { key(screen, dark) {
                    when (screen) {
                        "gallery", "albums", "album" -> GalleryModeScreen(photos, LibrarySource.APP, false, null, folders, listOf(assignment),
                            { LibraryModeHeader(AppMode.GALLERY, LibrarySource.APP, {}, {}, {}, {}) },
                            { _, _ -> }, {}, { _, _ -> }, { _, _ -> }, { "folder" }, {}, {}, { _, _ -> }, {},
                            searchVisible = false)
                        "viewer" -> GalleryMediaViewer(photos.first(), photos, {}, {}, {}, {}, emptySet(), { _, _ -> }, folders, { _, _ -> }, {}, {}, {})
                        "music", "playlists", "playlist" -> MusicModeScreen(tracks, tracks, playlists,
                            { LibraryModeHeader(AppMode.MUSIC, LibrarySource.APP, {}, {}, {}, {}) }, false, null,
                            { _, _ -> }, {}, { "new" }, { _, _ -> }, {}, { _, _ -> }, { _, _ -> }, { _, _, _ -> }, {}, {},
                            searchVisible = false)
                        "add" -> PlaylistTracksScreen(playlists.first().copy(trackIds = emptyList()), LibrarySource.APP, {}, tracks, tracks,
                            emptyMap(), {}, { _, _ -> }, {})
                        "player" -> MusicPlayerControls(tracks.first(), player, 62000, 175000, false, false, 0, 1f, {}, {}, {}, {})
                        "eq" -> EqualizerScreen(EqualizerSettings(), 48000, {}, {})
                    }
                } }
            } }
        }
        try {
            listOf(true, false).forEach { theme ->
                listOf("gallery", "albums", "album", "viewer", "music", "playlists", "playlist", "add", "player", "eq").forEach { page ->
                    compose.runOnIdle { dark = theme; screen = page }
                    compose.waitForIdle()
                    if (page in listOf("albums", "album")) compose.onNodeWithText("Альбомы").performClick()
                    if (page == "album") compose.onNodeWithText("Путешествия").performScrollTo().performClick()
                    if (page in listOf("playlists", "playlist")) compose.onNodeWithText("Плейлисты").performClick()
                    if (page == "playlist") compose.onNodeWithText("В дороге").performClick()
                    if (page == "viewer") compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Фото 0.jpg").fetchSemanticsNodes().isNotEmpty() }
                    // PixelCopy captureToImage requires a display redraw callback unavailable in
                    // Robolectric. Draw the real native View tree into its software Canvas instead.
                    val bitmap = compose.runOnIdle {
                        val root = compose.activity.findViewById<android.view.View>(android.R.id.content)
                        Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(android.graphics.Canvas(it)) }
                    }
                    File(output, "${if (theme) "dark" else "light"}-$page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        } finally { player.release() }
    }
}
