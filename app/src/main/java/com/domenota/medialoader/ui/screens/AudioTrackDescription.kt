package com.domenota.medialoader.ui.screens

/** Only player supplied metadata is shown; missing fields never acquire guessed values. */
internal fun audioTrackDescription(label: String?, language: String?, codec: String?, channels: Int, number: Int): String {
    val title = label?.takeIf { it.isNotBlank() } ?: "Дорожка $number"
    val details = listOfNotNull(language?.takeIf { it.isNotBlank() && it != "und" },
        codec?.takeIf { it.isNotBlank() }, channels.takeIf { it > 0 }?.let { "$it кан." })
        .filter { it != title }.distinct()
    return (listOf(title) + details).joinToString(" · ")
}
