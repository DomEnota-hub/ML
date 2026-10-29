package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.RecognitionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShazamClientTest {
    @Test fun parsesTrackMetadataFromSuccessfulResponse() {
        val body = """
            {
              "matches":[{"id":"match-1"}],
              "track":{
                "key":"123456",
                "title":"Night Drive",
                "subtitle":"Artist",
                "images":{"coverarthq":"https://example.test/cover.jpg"},
                "sections":[
                  {"type":"SONG","metadata":[
                    {"title":"Album","text":"After Midnight"},
                    {"title":"Label","text":"Example"}
                  ]}
                ]
              }
            }
        """.trimIndent()

        val result = ShazamClient().parseResponse(body)

        assertEquals("123456", result?.trackId)
        assertEquals("Night Drive", result?.title)
        assertEquals("Artist", result?.artist)
        assertEquals("After Midnight", result?.album)
        assertEquals("https://example.test/cover.jpg", result?.artworkUrl)
        assertEquals(RecognitionSource.SHAZAM_DIRECT, result?.source)
    }

    @Test fun emptyMatchesAreNotTreatedAsRecognition() {
        assertNull(ShazamClient().parseResponse("""{"matches":[],"track":{"key":"1","title":"Wrong","subtitle":"Wrong"}}"""))
    }
}
