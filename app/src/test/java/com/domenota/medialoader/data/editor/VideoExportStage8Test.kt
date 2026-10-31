package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoExportStage8Test {
    @Test fun resolutionPreservesFirstClipAspectAndEvenEncoderDimensions() {
        val portrait = VideoExportSettings(resolution = VideoExportResolution.HD_720)
            .dimensions(1080, 1920)
        assertEquals(720, portrait.width)
        assertEquals(1280, portrait.height)
        val landscape = VideoExportSettings(resolution = VideoExportResolution.FULL_HD_1080)
            .dimensions(1920, 1080)
        assertEquals(1920, landscape.width)
        assertEquals(1080, landscape.height)
        val source = VideoExportSettings().dimensions(1919, 1079)
        assertEquals(1920, source.width)
        assertEquals(1080, source.height)
        assertEquals(0, source.width % 2)
        assertEquals(0, source.height % 2)
    }

    @Test fun outputUsesPostCropAndRotationCanvas() {
        val settings = VideoExportSettings(resolution = VideoExportResolution.HD_720)
        val rotated = settings.dimensions(1920, 1080, .8f, 1f, 90)
        assertTrue(rotated.width < rotated.height)
        assertEquals(720, minOf(rotated.width, rotated.height))
        assertThrows(IllegalArgumentException::class.java) {
            settings.dimensions(0, 1080)
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.dimensions(1920, 1080, Float.NaN, 1f)
        }
    }

    @Test fun bitrateIncreasesWithQualityResolutionAndFrameRate() {
        val size = VideoExportDimensions(1920, 1080)
        val compact = VideoExportSettings(quality = VideoExportQuality.COMPACT)
            .targetBitrate(size)
        val high = VideoExportSettings(quality = VideoExportQuality.HIGH)
            .targetBitrate(size)
        assertTrue(high > compact)
        val sixty = VideoExportSettings(frameRate = VideoExportFrameRate.FPS_60)
            .targetBitrate(size)
        val thirty = VideoExportSettings(frameRate = VideoExportFrameRate.FPS_30)
            .targetBitrate(size)
        assertTrue(sixty > thirty)
        assertTrue(VideoExportSettings().targetBitrate(
            VideoExportDimensions(3840, 2160), 60) <= 65_000_000)
    }

    @Test fun imageDurationIsEditableWithoutPretendingThereIsVideoTrim() {
        val photo = VideoClipDraft("p", "content://photos/1", 3000L,
            mediaKind = VideoClipMediaKind.IMAGE)
        val video = VideoClipDraft("v", "content://videos/1", 4000L)
        val p = VideoProjectDraft(listOf(photo, video))
        val changed = p.setPhotoDuration("p", 5500L)
        assertEquals(9500L, changed.durationMs)
        assertEquals(5500L, changed.clips[0].outMs)
        assertEquals(4000L, changed.clips[1].durationMs)
        assertThrows(IllegalArgumentException::class.java) { p.trimClip("p", 0L, 1000L) }
        assertThrows(IllegalArgumentException::class.java) { p.splitAt("p", 1500L, "extra") }
        assertThrows(IllegalArgumentException::class.java) { p.setSpeed("p", 2f) }
        assertThrows(IllegalArgumentException::class.java) { p.setPhotoDuration("p", 31_000L) }
    }

    @Test fun mixedTimelineSupportsCutsTransitionsAndAudioIsolation() {
        val movie = VideoClipDraft("v", "content://movie/1", 5000L)
        val photo = VideoClipDraft("p", "content://photo/1", 3000L,
            mediaKind = VideoClipMediaKind.IMAGE)
        val sound = VideoAudioTrackDraft("a", "content://track/1", 1000L, 7000L)
        val mixed = VideoProjectDraft(listOf(movie, photo), listOf(sound))
            .setTransitionAfter("v", VideoTransitionDraft(VideoTransitionType.DIP_BLACK))
        assertEquals(8000L, mixed.durationMs)
        assertEquals(1, VideoTransitionTiming.enabledCuts(mixed))
        assertEquals(1, mixed.visibleAudioTracks().size)
        assertEquals("p", mixed.locateAt(5500L).clipId)
        val reordered = mixed.move("p", 0)
        assertEquals(8000L, reordered.durationMs)
        assertEquals(2, reordered.clips.size)
        assertEquals(VideoClipMediaKind.IMAGE, reordered.clips.first().mediaKind)
    }
}
