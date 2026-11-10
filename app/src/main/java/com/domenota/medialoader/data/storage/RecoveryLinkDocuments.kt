package com.domenota.medialoader.data.storage

import org.json.JSONArray
import org.json.JSONObject

/** Existing on-disk catalog/playlist codecs. IDs stay stable; only exact physical aliases change. */
internal object RecoveryLinkDocuments {
    fun catalog(raw: String, id: String, from: String, to: String): String {
        val root = JSONObject(raw)
        check(root.getInt("version") == 1)
        val entries = root.getJSONArray("entries")
        val matches = (0 until entries.length()).map { entries.getJSONObject(it) }.filter { it.getString("key") == id }
        check(matches.size == 1) { "Неоднозначный каталог" }
        val row = matches.single()
        check(row.getString("uri") == from || row.getString("uri") == to)
        row.put("uri", to)
        val aliases = row.getJSONArray("aliases")
        check((0 until aliases.length()).any { aliases.getString(it) == from || aliases.getString(it) == to })
        for (i in 0 until aliases.length()) if (aliases.getString(i) == from) aliases.put(i, to)
        return root.toString()
    }
    fun playlists(raw: String, id: String, from: String, to: String): String {
        val lists = JSONArray(raw)
        var references = 0
        for (i in 0 until lists.length()) {
            val row = lists.getJSONObject(i)
            val tracks = row.getJSONArray("trackIds")
            if (!(0 until tracks.length()).any { tracks.getString(it) == id }) continue
            val aliases = row.getJSONObject("trackReferences").getJSONArray(id)
            check((0 until aliases.length()).any { aliases.getString(it) == from || aliases.getString(it) == to })
            for (j in 0 until aliases.length()) if (aliases.getString(j) == from) aliases.put(j, to)
            references++
        }
        check(references > 0) { "Потеряны ссылки плейлиста" }
        return lists.toString()
    }
}
