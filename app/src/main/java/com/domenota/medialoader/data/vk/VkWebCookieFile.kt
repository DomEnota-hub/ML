package com.domenota.medialoader.data.vk

/** Strict reader for the app's own Netscape cookie export. */
internal object VkWebCookieFile {
    data class Row(val domain: String, val name: String, val value: String)

    private val domains = setOf(".vk.com", ".id.vk.com", ".vk.ru", ".id.vk.ru",
        ".vkvideo.ru")
    private val namePattern = Regex("[A-Za-z0-9_-]{1,80}")

    fun parse(lines: Sequence<String>): List<Row> = lines.take(80).mapNotNull { line ->
        if (line.startsWith('#')) return@mapNotNull null
        val fields = line.split('\t', limit = 7)
        if (fields.size != 7 || fields[0] !in domains || fields[2] != "/" ||
            fields[3] != "TRUE" || !namePattern.matches(fields[5])) return@mapNotNull null
        val value = fields[6]
        if (value.isEmpty() || value.length > 4_096 ||
            value.any { it <= ' ' || it == ';' || it == '\u007f' }) return@mapNotNull null
        Row(fields[0], fields[5], value)
    }.take(60).toList()
}
