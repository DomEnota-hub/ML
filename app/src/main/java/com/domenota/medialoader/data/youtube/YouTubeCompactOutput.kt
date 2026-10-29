package com.domenota.medialoader.data.youtube

import org.json.JSONArray
import org.json.JSONObject

/** Keep only the fields used by the format picker; yt-dlp otherwise prints every stream URL. */
internal object YouTubeCompactOutput {
    const val METADATA = "%(.{id,title,uploader,channel,duration,thumbnail})j"
    const val FORMATS = "%(formats.:.{format_id,height,filesize,filesize_approx,ext,vcodec,acodec,fps,width,protocol,format_note})j"

    fun toJson(output: String): String {
        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.size == 2) { "Expected metadata and formats" }
        val metadata = JSONObject(lines[0])
        require(metadata.optString("id").isNotBlank()) { "Missing video ID" }
        val formats = JSONArray(lines[1])
        require(formats.length() > 0) { "Missing formats" }
        return metadata.put("formats", formats).toString()
    }
}
