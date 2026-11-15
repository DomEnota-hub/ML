package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionSource
import org.json.JSONObject

/** Metadata-only assets are never exposed as downloadable media. Read only explicit
 * publication-level native Instagram wrappers already used in the resolver fixtures. */
internal data class InstagramPublicationMusic(val title: String?, val artist: String?,
    val audioUrl: String?, val artworkUrl: String?) {
    fun metadata(): MusicRecognitionResult? {
        val t=title ?: return null; val a=artist ?: return null
        if (isGenericInstagramMusicTitle(t)) return null
        return MusicRecognitionResult(title=t,artist=a,artworkUrl=artworkUrl,source=RecognitionSource.SOURCE_METADATA)
    }
    fun audioCandidate(group: String, page: String): MediaItem? = audioUrl?.let {
        MediaItem("${group}_lookup_music",InstagramProvider.ID,MediaType.AUDIO,"Instagram.mp3",it,
            audioArtist=artist,audioTitle=title,audioArtworkUrl=artworkUrl,sourceGroupId=group,sourcePageUrl=page)
    }
    companion object {
        fun fromProduct(product: JSONObject): InstagramPublicationMusic? {
            val assets = buildList<JSONObject> {
                product.optJSONObject("music_asset_info")?.let(::add)
                for (wrapper in listOf("music_metadata","clips_metadata")) {
                    product.optJSONObject(wrapper)?.optJSONObject("music_info")
                        ?.optJSONObject("music_asset_info")?.let(::add)
                }
                product.optJSONArray("story_music_stickers")?.let { stickers ->
                    for (i in 0 until stickers.length()) stickers.optJSONObject(i)
                        ?.optJSONObject("music_asset_info")?.let(::add)
                }
            }
            fun text(asset: JSONObject,key: String)=(asset.opt(key) as? String)?.trim()
                ?.takeIf { it.isNotEmpty() && it.length<=300 && it.none(Char::isISOControl) }
            val candidates=assets.map { asset -> InstagramPublicationMusic(text(asset,"title"),text(asset,"display_artist"),
                // CDN URLs can be long; do not apply title-length limits to them.
                (asset.opt("progressive_download_url") as? String)?.takeIf{safePublicationAsset(it)},
                listOf("cover_artwork_uri","cover_artwork_thumbnail_uri").firstNotNullOfOrNull { key ->
                    (asset.opt(key) as? String)?.takeIf{safePublicationAsset(it)} }) }
                .filter { it.metadata()!=null || it.audioUrl!=null }.distinct()
            // Distinct publication-level candidates are ambiguous; never pick an unrelated song.
            return candidates.singleOrNull()
        }
    }
}
internal fun isGenericInstagramMusicTitle(title: String): Boolean {
    val normalized=title.lowercase(java.util.Locale.ROOT).replace('_',' ').replace('-',' ')
        .replace(Regex("\\s+")," ").trim()
    return normalized in setOf("original audio","original sound","original music","оригинальное аудио",
        "оригинальный звук","оригинальная аудиодорожка","оригинальная музыка")
}

private fun safePublicationAsset(value: String): Boolean {
    if (!InstagramProvider.safeMediaUrl(value)) return false
    val host=java.net.URI(value).host.lowercase(java.util.Locale.ROOT)
    return host=="fbcdn.net" || host.endsWith(".fbcdn.net") ||
        host=="cdninstagram.com" || host.endsWith(".cdninstagram.com")
}
