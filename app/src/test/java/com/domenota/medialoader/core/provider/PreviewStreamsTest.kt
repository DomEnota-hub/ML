package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreviewStreamsTest {
    private fun info(json: String) = YtDlpJson.parse(json)
    @Test fun preservesUrlsAndPerFormatHeadersOverRootHeaders() {
        val result = info("""{"id":"x","http_headers":{"Referer":"https://example.org/","User-Agent":"base"},"formats":[
            {"format_id":"18","height":360,"vcodec":"avc1","acodec":"aac","url":"https://cdn.example.org/v.mp4","protocol":"https","http_headers":{"User-Agent":"track"}}]}""")
        val source = PreviewStreams.select(result, MediaType.VIDEO, 360)
        assertEquals("https://cdn.example.org/v.mp4", source.primary.url)
        assertEquals("track", source.primary.headers["User-Agent"])
        assertEquals("https://example.org/", source.primary.headers["Referer"])
        assertNull(source.audio)
    }
    @Test fun separateVideoGetsSeparateAudioTrack() {
        val result = info("""{"id":"x","formats":[
            {"format_id":"v","height":1080,"vcodec":"avc1","acodec":"none","url":"https://cdn.example.org/v.mp4"},
            {"format_id":"a","vcodec":"none","acodec":"aac","url":"https://cdn.example.org/a.m4a"}]}""")
        val source = PreviewStreams.select(result, MediaType.VIDEO, 1080)
        assertEquals("https://cdn.example.org/v.mp4", source.primary.url)
        assertEquals("https://cdn.example.org/a.m4a", source.audio?.url)
        val audio = PreviewStreams.select(result, MediaType.AUDIO)
        assertEquals("https://cdn.example.org/a.m4a", audio.primary.url)
        assertNull(audio.audio)
    }
    @Test fun protocolHintsManifestsAndRejectsNonHttpOrDrmFormats() {
        val result = info("""{"id":"x","formats":[
            {"format_id":"bad","height":1080,"vcodec":"avc1","acodec":"aac","url":"file:///secret"},
            {"format_id":"drm","height":1080,"vcodec":"avc1","acodec":"aac","url":"https://cdn.example.org/drm","has_drm":true},
            {"format_id":"hls","height":720,"vcodec":"avc1","acodec":"aac","url":"https://cdn.example.org/manifest?key=x","protocol":"m3u8_native"}]}""")
        val source = PreviewStreams.select(result, MediaType.VIDEO, 1080)
        assertEquals("https://cdn.example.org/manifest?key=x", source.primary.url)
        assertEquals("application/x-mpegURL", source.primary.mimeType)
    }
    @Test(expected = IllegalArgumentException::class) fun missingPlayableFormatIsAnError() {
        PreviewStreams.select(info("""{"id":"x","formats":[]}"""), MediaType.VIDEO)
    }
    @Test fun muxedLowQualityCanSupplyAudioForHigherVideoOnlyFormat() {
        val source = PreviewStreams.select(info("""{"id":"x","formats":[
            {"format_id":"v","height":1080,"vcodec":"avc1","acodec":"none","url":"https://cdn.example.org/v.mp4"},
            {"format_id":"m","height":360,"vcodec":"avc1","acodec":"aac","url":"https://cdn.example.org/m.mp4"}]}"""), MediaType.VIDEO, 1080)
        assertEquals("https://cdn.example.org/m.mp4", source.audio?.url)
    }
    @Test fun sharedDashManifestIsLoadedOnceAndRetainsSelectedHeightLimit() {
        val source = PreviewStreams.select(info("""{"id":"x","formats":[
            {"format_id":"v","height":720,"vcodec":"avc1","acodec":"none","url":"https://cdn.example.org/v","protocol":"http_dash_segments","manifest_url":"https://cdn.example.org/all.mpd"},
            {"format_id":"a","vcodec":"none","acodec":"aac","url":"https://cdn.example.org/a","protocol":"http_dash_segments","manifest_url":"https://cdn.example.org/all.mpd"}]}"""), MediaType.VIDEO, 720)
        assertNull(source.audio)
        assertEquals(720, source.maxVideoHeight)
    }
    @Test fun previewReusesAnalysisButExplicitAnalysisStillRefreshes() = runBlocking {
        var calls = 0
        var time = 100L
        val delegate = object : StreamExtractor {
            override suspend fun extract(url: String): StreamInfo { calls++; return info("""{"id":"x","formats":[]}""") }
        }
        val extractor = PreviewStreamExtractor(delegate) { time }
        extractor.extract("https://example.org/watch")
        extractor.forPreview("https://example.org/watch")
        assertEquals(1, calls)
        extractor.extract("https://example.org/watch")
        assertEquals(2, calls)
        time += 6 * 60_000
        extractor.forPreview("https://example.org/watch")
        assertEquals(3, calls)
    }
}
