package com.domenota.medialoader.core.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeFormatDiagnosticsTest {
    @Test fun reportsOnlyVideoQualityProtocolAndKnownClient() {
        val json = """{"formats":[
            {"format_id":"18","height":360,"vcodec":"avc1","acodec":"mp4a","protocol":"https","format_note":"360p, WEB","url":"https://secret.example/token=private"},
            {"format_id":"95","height":720,"vcodec":"avc1","acodec":"mp4a","protocol":"m3u8_native","format_note":"720p, WEBS"},
            {"format_id":"140","vcodec":"none","acodec":"mp4a","protocol":"https","format_note":"medium, WEB"}
        ]}"""
        assertEquals("360p:https:WEB,720p:hls:WEBS", YouTubeFormatDiagnostics.summary(json))
    }

    @Test fun rejectsUnknownProvenanceAndProtocol() {
        val json = """{"formats":[{"height":1080,"vcodec":"vp9","acodec":"none","protocol":"https://secret.example/path","format_note":"1080p, token=private"}]}"""
        assertEquals("1080p:other:unknown", YouTubeFormatDiagnostics.summary(json))
    }
}
