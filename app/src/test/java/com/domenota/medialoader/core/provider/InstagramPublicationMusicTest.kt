package com.domenota.medialoader.core.provider
import com.domenota.medialoader.core.recognition.RecognitionSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class InstagramPublicationMusicTest {
    private val code="Ab_12-z"
    private val page="https://instagram.com/p/$code/"
    private val product="""{"pk":"${InstagramProvider.mediaId(code)}","image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/photo.jpg"}]},"music_metadata":{"music_info":{"music_asset_info":{"title":"Night Drive","display_artist":"Artist"}}}}"""
    @Test fun metadataOnlyPhotoUsesExactPublicationWithoutInventingDownload()=runBlocking {
        val provider=InstagramProvider(pageSource={"<script data-sjs>{\"item\":$product}</script>"})
        val music=provider.lookupPublicationMusic(page)!!
        assertEquals("Night Drive",music.metadata()?.title)
        assertEquals("Artist",music.metadata()?.artist)
        assertEquals(RecognitionSource.SOURCE_METADATA,music.metadata()?.source)
        assertNull(music.audioCandidate(code,page))
        assertEquals(1,provider.resolve(page).size) // ordinary download is still photo only
    }
    @Test fun unrelatedRecommendationOrAmbiguousAssetsAreNeverUsed(){
        val unrelated=product.replace(InstagramProvider.mediaId(code),InstagramProvider.mediaId("OtherCode"))
        assertNull(InstagramProvider.extractEmbeddedPublicationMusic("<script data-sjs>{\"item\":$unrelated}</script>",code))
        val nested=JSONObject(product).apply{remove("music_metadata");put("recommendations",JSONObject(product))}
        assertNull(InstagramPublicationMusic.fromProduct(nested))
        val ambiguous=JSONObject(product).apply{put("music_asset_info",JSONObject("""{"title":"Other","display_artist":"Other"}"""))}
        assertNull(InstagramPublicationMusic.fromProduct(ambiguous))
    }
    @Test fun genericOriginalAudioIsOnlyAnAcousticCandidateAndUnsafeUrlIsNeverUsed(){
        val music=InstagramPublicationMusic.fromProduct(JSONObject("""{"music_asset_info":{"title":"Original audio","display_artist":"Account","progressive_download_url":"https://video.fbcdn.net/sample.m4a"}}"""))!!
        assertNull(music.metadata())
        assertNotNull(music.audioCandidate(code,page))
        assertNull(InstagramPublicationMusic.fromProduct(JSONObject("""{"music_asset_info":{"title":"Original audio","display_artist":"Account","progressive_download_url":"https://fbcdn.net.evil.test/a.m4a"}}""")))
    }
    @Test fun authorizedFallbackRequiresMatchingIdentity()=runBlocking {
        val provider=InstagramProvider(cookies={"sessionid=test"},apiClient=InstagramApiClient{_,_->"{\"items\":[$product]}"},pageSource={""})
        assertEquals("Night Drive",provider.lookupPublicationMusic(page)?.metadata()?.title)
        assertNull(provider.lookupPublicationMusic("https://instagram.com/p/OtherCode/"))
    }
}
