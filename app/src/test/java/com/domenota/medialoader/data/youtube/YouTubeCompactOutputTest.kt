package com.domenota.medialoader.data.youtube

import com.domenota.medialoader.core.provider.YtDlpJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCompactOutputTest {
    @Test fun reconstructsPlayableFormatsWithoutStreamUrls() {
        val output = """
            {"id":"abc","title":"Song","duration":120}
            [{"format_id":"137","height":1080,"ext":"mp4","vcodec":"avc1","acodec":"none","filesize":123456},{"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a"}]
        """.trimIndent()
        val json = YouTubeCompactOutput.toJson(output)
        val info = YtDlpJson.parse(json)
        assertEquals("abc", info.id)
        assertEquals(2, info.formats.size)
        assertEquals(1080, info.formats.first().height)
        assertTrue(info.formats.any { it.hasAudio })
        assertTrue(!json.contains("url"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingFormatsSoFullJsonCanBeRetried() {
        YouTubeCompactOutput.toJson("{\"id\":\"abc\"}\n[]")
    }
}
