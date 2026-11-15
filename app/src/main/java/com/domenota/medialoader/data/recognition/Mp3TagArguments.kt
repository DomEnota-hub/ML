package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionResult

/** Argument vectors avoid shell quoting ambiguity for paths and Unicode tags. */
internal object Mp3TagArguments {
    fun build(input: String, output: String, result: MusicRecognitionResult,
        artwork: String?, preserveExistingArtwork: Boolean): List<String> = buildList {
        addAll(listOf("-nostdin","-hide_banner","-loglevel","error","-y","-i",input))
        if (artwork != null) addAll(listOf("-i",artwork))
        addAll(listOf("-map","0:a:0","-map_metadata","0","-c:a","copy"))
        if (artwork != null) addAll(listOf("-map","1:v:0","-c:v","copy","-disposition:v:0","attached_pic",
            "-metadata:s:v:0","title=Album cover","-metadata:s:v:0","comment=Cover (front)"))
        else if (preserveExistingArtwork) addAll(listOf("-map","0:v?","-c:v","copy"))
        else add("-vn")
        addAll(listOf("-id3v2_version","3","-write_id3v1","1","-metadata","title=${result.title}","-metadata","artist=${result.artist}"))
        result.album?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-metadata","album=$it")) }
        add(output)
    }
    fun failureKind(output: String): String {
        val text = output.lowercase(java.util.Locale.ROOT)
        return when {
            "permission denied" in text || "operation not permitted" in text -> "permission_denied"
            "no space left" in text -> "no_space"
            "unknown encoder" in text || "encoder not found" in text -> "encoder_unavailable"
            "invalid audio stream" in text || "exactly one mp3 audio stream" in text -> "invalid_mp3_audio_stream"
            "invalid data found" in text -> "invalid_input_data"
            "could not write header" in text || "error writing header" in text -> "muxer_header"
            "stream map" in text || "matches no streams" in text -> "stream_mapping"
            "not supported" in text || "unsupported" in text -> "unsupported_stream"
            "no such file" in text -> "file_not_found"
            else -> "unclassified_ffmpeg_error"
        }
    }
}
