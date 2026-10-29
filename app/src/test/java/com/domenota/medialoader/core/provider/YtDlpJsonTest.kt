package com.domenota.medialoader.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpJsonTest {
    @Test fun parsesInfoAndFormats() {
        val info = YtDlpJson.parse(SAMPLE)
        assertEquals("dQw4w9WgXcQ", info.id)
        assertEquals("Chan", info.uploader)
        assertEquals(212L, info.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", info.thumbnailUrl)

        val byId = info.formats.associateBy { it.formatId }
        assertTrue(byId.getValue("140").hasAudio)
        assertFalse(byId.getValue("140").hasVideo)
        assertEquals(3_000_000L, byId.getValue("140").sizeBytes)
        assertTrue(byId.getValue("18").hasAudio && byId.getValue("18").hasVideo)
        assertEquals(360, byId.getValue("18").height)
        assertEquals(30_000_000L, byId.getValue("299").sizeBytes) // filesize_approx is used when filesize is absent
        assertEquals(60, byId.getValue("299").fps)
    }

    @Test fun storyboardsAndNullCodecsAreNeitherVideoNorAudio() {
        val formats = YtDlpJson.parse(SAMPLE).formats.associateBy { it.formatId }
        assertFalse(formats.getValue("sb0").hasVideo || formats.getValue("sb0").hasAudio)
        assertFalse(formats.getValue("x").hasVideo || formats.getValue("x").hasAudio)
        assertNull(formats.getValue("x").height)
    }

    @Test fun preservesVideoWidthForResolutionDiagnostics() {
        val info = YtDlpJson.parse("""{"id":"r","formats":[{"format_id":"hls","width":1280,"height":672,"vcodec":"avc1","acodec":"none"}]}""")
        assertEquals(1280, info.formats.single().width)
        assertEquals(672, info.formats.single().height)
    }

    @Test(expected = ExtractionException::class)
    fun missingIdFails() {
        YtDlpJson.parse("""{"title":"no id"}""")
    }

    companion object {
        val SAMPLE = """
            {"id":"dQw4w9WgXcQ","title":"Test Video: A/B \"quote\"","uploader":"Chan","duration":212.0,
             "thumbnail":"https://i.ytimg.com/vi/x/hq.jpg","formats":[
              {"format_id":"sb0","ext":"mhtml","vcodec":"none","acodec":"none"},
              {"format_id":"139","ext":"m4a","vcodec":"none","acodec":"mp4a.40.5","filesize":1000000},
              {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","filesize":3000000},
              {"format_id":"18","ext":"mp4","vcodec":"avc1.42001E","acodec":"mp4a.40.2","height":360,"filesize":5000000,"fps":30},
              {"format_id":"137","ext":"mp4","vcodec":"avc1.640028","acodec":"none","height":1080,"filesize":20000000,"fps":30},
              {"format_id":"299","ext":"mp4","vcodec":"avc1.64002a","acodec":"none","height":1080,"filesize_approx":30000000,"fps":60},
              {"format_id":"313","ext":"webm","vcodec":"vp9","acodec":"none","height":2160,"filesize":90000000,"fps":30},
              {"format_id":"x","ext":"mp4","vcodec":null,"acodec":null,"height":null}
            ]}
        """.trimIndent()
    }
}
