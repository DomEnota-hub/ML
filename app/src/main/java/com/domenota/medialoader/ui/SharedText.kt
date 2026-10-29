package com.domenota.medialoader.ui

private val urlPattern = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)

/** First http(s) link inside text shared from another app, without trailing punctuation. */
fun extractFirstUrl(text: String): String? =
    urlPattern.find(text)?.value?.trimEnd('.', ',', ')', ';', '!', '?')
