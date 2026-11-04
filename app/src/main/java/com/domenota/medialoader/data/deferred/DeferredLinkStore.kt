package com.domenota.medialoader.data.deferred

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Separate durable buffer; never stores or modifies downloaded files. */
class DeferredLinkStore(context: Context) {
    private val prefs = context.getSharedPreferences("deferred_download_links", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(runCatching {
        val json = JSONArray(prefs.getString("links", "[]"))
        (0 until json.length()).map { json.getString(it) }.distinct()
    }.getOrDefault(emptyList()))
    val links = state.asStateFlow()
    fun add(raw: String): Boolean {
        val link = raw.trim()
        val uri = Uri.parse(link)
        if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank()) return false
        write((state.value + link).distinct()); return true
    }
    fun remove(link: String) = write(state.value - link)
    private fun write(links: List<String>) {
        prefs.edit().putString("links", JSONArray(links).toString()).apply()
        state.value = links
    }
    companion object {
        fun source(link: String): String {
            val host = Uri.parse(link).host.orEmpty().lowercase().removePrefix("www.")
            fun site(domain: String) = host == domain || host.endsWith(".$domain")
            return when {
                site("instagram.com") -> "Instagram"
                site("youtube.com") || site("youtu.be") -> "YouTube"
                site("vk.com") || site("vk.ru") || site("vkvideo.ru") -> "VK"
                site("rutube.ru") -> "RUTUBE"
                else -> host.ifBlank { "Ссылка" }
            }
        }
    }
}
