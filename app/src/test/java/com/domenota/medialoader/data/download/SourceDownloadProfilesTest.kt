package com.domenota.medialoader.data.download

import android.content.Context
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.preferences.VideoPreferences
import com.domenota.medialoader.data.share.InstagramShareSettings
import com.domenota.medialoader.data.storage.DownloadFolderOverride
import com.domenota.medialoader.ui.model.defaultSelectedIds
import com.domenota.medialoader.ui.model.togglePreviewSelection
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceDownloadProfilesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun store() = SourceDownloadProfiles(context)
    @Before fun reset() {
        context.getSharedPreferences("source_download_profiles", 0).edit().clear().commit()
        VideoPreferences(context).preferredHeight = 720
        VideoPreferences(context).qualityDirection = VideoQualityDirection.LOWER
        InstagramShareSettings.setAutomatic(context, true)
    }
    private fun items(provider: String) = listOf(
        MediaItem("720", provider, MediaType.VIDEO, "clip_720.mp4", "url", qualityLabel="720p",
            group="video", preselected=true),
        MediaItem("1080", provider, MediaType.VIDEO, "clip_1080.mp4", "url", qualityLabel="1080p", group="video"),
        MediaItem("audio", provider, MediaType.AUDIO, "clip.mp3", "url"),
    )
    @Test fun defaultInheritanceAndExplicitMaximumRemainDistinct() {
        val s = store()
        assertEquals(setOf("720"), s.defaults(items("youtube")).defaultSelectedIds())
        s.save("youtube", SourceDownloadProfile(height=0))
        assertEquals(setOf("1080"), store().defaults(items("youtube")).defaultSelectedIds())
        assertEquals(setOf("720"), s.defaults(items("vk")).defaultSelectedIds())
        VideoPreferences(context).preferredHeight = 1080
        assertEquals(setOf("1080"), s.defaults(items("vk")).defaultSelectedIds())
        s.save("youtube", SourceDownloadProfile())
        assertEquals(SourceDownloadProfile(), store().profile("youtube"))
    }
    @Test fun audioPreferenceIsOnlyInitialSelectionAndDoesNotRemoveAlternatives() {
        for (provider in listOf("youtube","vk","rutube")) {
            val s = store()
            s.save(provider, SourceDownloadProfile(format=SourceFormat.AUDIO))
            val defaults = s.defaults(items(provider))
            assertEquals(listOf("720","1080","audio"), defaults.map { it.id })
            assertEquals(setOf("audio"), defaults.defaultSelectedIds())
            assertEquals(setOf("1080"), togglePreviewSelection(defaults, defaults.defaultSelectedIds(), "1080"))
            assertEquals(setOf("720"), s.defaults(items(provider).filter { it.type != MediaType.AUDIO }).defaultSelectedIds())
        }
    }
    @Test fun sourceModesDoNotChangeGlobalModeAndConfirmationWins() {
        val s = store()
        assertTrue(s.automatic("instagram")); assertTrue(s.automatic("youtube"))
        s.save("instagram", SourceDownloadProfile(mode=SourceDownloadMode.MANUAL))
        assertFalse(s.automatic("instagram")); assertTrue(s.automatic("youtube"))
        s.save("youtube", SourceDownloadProfile(mode=SourceDownloadMode.AUTOMATIC, confirmation=true))
        assertFalse(s.automatic("youtube"))
        s.save("youtube", SourceDownloadProfile(mode=SourceDownloadMode.AUTOMATIC, format=SourceFormat.AUDIO))
        assertFalse(s.automatic("youtube"))
        assertTrue(InstagramShareSettings.isAutomatic(context))
        assertFalse(s.automatic("vk"))
    }
    @Test fun folderAndConfirmationAreExplicitAndSafKeepsItsGrant() {
        val s = store()
        s.save("vk", SourceDownloadProfile(folder="VK видео", confirmation=false))
        assertEquals("Pictures/VK видео/", store().folderPath("vk", false))
        assertNull(s.folderPath("vk", true))
        assertNull(s.folderPath("youtube", false))
        assertTrue(s.skipNameConfirmation(items("vk")))
        assertFalse(s.skipNameConfirmation(items("vk")+items("youtube")))
        assertFalse(s.skipNameConfirmation(emptyList()))
        assertTrue(runCatching { s.save("vk", SourceDownloadProfile(folder="../Music")) }.isFailure)
        assertTrue(runCatching { DownloadFolderOverride("Pictures/../Music/") }.isFailure)
        assertTrue(runCatching { s.save("instagram", SourceDownloadProfile(height=720)) }.isFailure)
        assertTrue(runCatching { s.save("rutube", SourceDownloadProfile(mode=SourceDownloadMode.AUTOMATIC)) }.isFailure)
    }
}
