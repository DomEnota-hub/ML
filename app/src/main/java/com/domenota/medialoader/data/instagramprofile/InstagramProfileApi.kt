package com.domenota.medialoader.data.instagramprofile

import com.domenota.medialoader.core.provider.ProviderException
import com.domenota.medialoader.core.provider.fetchInstagramApi
import com.domenota.medialoader.core.logging.AppLog
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** Instagram's profile web queries are deliberately isolated: document IDs and shapes can change. */
class InstagramProfileApi(
    private val request: suspend (String, String, String?, String, String) -> String = ::requestProfile,
) {
    suspend fun profile(username: String, cookies: String): InstagramProfileInfo {
        val page = "https://www.instagram.com/$username/"
        val apiUrl = "https://www.instagram.com/api/v1/users/web_profile_info/?username=" + encode(username)
        var accountFailure: ProviderException? = null
        if (cookies.isNotBlank()) {
            try { return parseProfile(request("GET", apiUrl, null, cookies, page)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: ProviderException) { accountFailure = error }
            catch (_: Exception) { /* Try the HTML route. */ }
        }
        for (session in listOf(cookies, "").distinct()) {
            try { return parseProfileHtml(request("GET", page, null, session, page), username) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: ProviderException) {
                if (accountFailure == null || error.reason == ProviderException.Reason.ACCESS_REQUIRED)
                    accountFailure = error
            } catch (_: Exception) { /* Try the next profile route. */ }
        }
        throw accountFailure ?: InstagramProfileFormatException("Instagram изменил ответ профиля")
    }

    suspend fun page(profile: InstagramProfileInfo, cursor: String?, reels: Boolean,
        cookies: String): InstagramProfilePage {
        if (cursor?.startsWith("api:") == true)
            return privatePage(profile, cursor.removePrefix("api:"), reels, cookies)
        val variables = if (reels) JSONObject().put("data", JSONObject()
            .put("page_size", 12).put("include_feed_video", true)
            .put("target_user_id", profile.id))
        else JSONObject().put("data", JSONObject()
            .put("count", 12).put("include_reel_media_seen_timestamp", true)
            .put("include_relationship_info", true).put("latest_besties_reel_media", true)
            .put("latest_reel_media", true))
            .put("first", 12).put("include_multi_captions", true)
            .put("username", profile.username)
            .put("__relay_internal__pv__PolarisMultiCaptionCarouselEnabledrelayprovider", true)
            .put("__relay_internal__pv__PolarisShortDramaEnabledrelayprovider", false)
        variables.put("__relay_internal__pv__PolarisFeedShareMenurelayprovider", false)
        if (cursor != null) variables.put("after", cursor).put("before", JSONObject.NULL)
            .put("first", 12).put("last", JSONObject.NULL)
        val form = "variables=${encode(variables.toString())}&doc_id=${if (reels) REELS_DOC else FEED_DOC}&server_timestamps=true"
        try {
            val body = request("POST", "https://www.instagram.com/graphql/query", form,
                cookies, "https://www.instagram.com/${profile.username}/")
            return parsePage(body, reels)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: ProviderException) {
            AppLog.i("InstagramProfile", "Web feed request failed · reason=${error.reason} · detail=${error.message.orEmpty().take(90)}")
            if (error.reason == ProviderException.Reason.RATE_LIMITED) throw error
        } catch (_: InstagramProfileFormatException) {
            AppLog.i("InstagramProfile", "Web feed response format changed")
        }
        // A GraphQL cursor cannot be used by the older feed endpoint. Restart its first
        // page when the web query changes, then continue with the private API cursor.
        return privatePage(profile, null, reels, cookies)
    }

    private suspend fun privatePage(profile: InstagramProfileInfo, cursor: String?, reels: Boolean,
        cookies: String): InstagramProfilePage {
        val path = if (reels) "clips/user/?target_user_id=${profile.id}&page_size=12"
            else "feed/user/${profile.id}/?count=12"
        val url = "https://www.instagram.com/api/v1/$path" +
            (cursor?.let { "&max_id=${encode(it)}" } ?: "")
        return try {
            parsePrivatePage(request("GET", url, null, cookies,
                "https://www.instagram.com/${profile.username}/"), reels)
        } catch (error: ProviderException) {
            AppLog.i("InstagramProfile", "Fallback feed request failed · reason=${error.reason} · detail=${error.message.orEmpty().take(90)}")
            throw error
        }
    }

    companion object {
        // Current web document IDs; keep them confined here for quick replacement if Instagram changes them.
        private const val FEED_DOC = "28975909992013618"
        private const val REELS_DOC = "7845543455542541"

        fun parseProfile(body: String): InstagramProfileInfo {
            val user = runCatching { JSONObject(body).optJSONObject("data")?.optJSONObject("user") }
                .getOrNull() ?: throw InstagramProfileFormatException("Instagram изменил ответ профиля")
            val id = user.optString("pk").ifBlank { user.optString("id") }
            val username = user.optString("username")
            if (!id.matches(Regex("[0-9]{1,25}")) || username.isBlank())
                throw InstagramProfileFormatException("Instagram изменил ответ профиля")
            return InstagramProfileInfo(id, username,
                user.optString("full_name").ifBlank { username },
                user.optString("profile_pic_url").ifBlank { user.optString("profile_pic_url_hd") }
                    .takeIf { it.startsWith("https://") },
                user.optBoolean("is_private"))
        }

        /** Public profile HTML embeds the user even when the JSON endpoint refuses access. */
        fun parseProfileHtml(html: String, username: String): InstagramProfileInfo {
            for (script in Jsoup.parse(html).select("script[type=application/json]")) {
                val root = runCatching { JSONObject(script.data()) }.getOrNull() ?: continue
                val user = findEmbeddedUser(root, username, 0) ?: continue
                return parseProfile(JSONObject().put("data", JSONObject().put("user", user)).toString())
            }
            throw InstagramProfileFormatException("Instagram изменил ответ профиля")
        }

        private fun findEmbeddedUser(node: Any?, username: String, depth: Int): JSONObject? {
            if (depth > 50) return null
            return when (node) {
                is JSONObject -> {
                    val user = node.optJSONObject("xig_user_by_username")
                    if (user != null && user.optString("username").equals(username, true) &&
                        user.optString("pk").matches(Regex("[0-9]{1,25}"))) user
                    else {
                        val keys = node.keys()
                        var match: JSONObject? = null
                        while (keys.hasNext() && match == null)
                            match = findEmbeddedUser(node.opt(keys.next()), username, depth + 1)
                        match
                    }
                }
                is JSONArray -> (0 until node.length()).firstNotNullOfOrNull {
                    findEmbeddedUser(node.opt(it), username, depth + 1)
                }
                else -> null
            }
        }

        fun parsePage(body: String, reels: Boolean): InstagramProfilePage {
            val root = runCatching { JSONObject(body) }.getOrNull()
                ?: throw InstagramProfileFormatException("Instagram изменил ответ поиска")
            val signal = listOf(root.optString("message"), root.optString("error_type"))
                .joinToString(" ").lowercase()
            if (listOf("please wait", "rate_limit", "feedback_required", "challenge_required",
                    "checkpoint_required").any(signal::contains))
                throw ProviderException(ProviderException.Reason.RATE_LIMITED,
                    "Instagram ограничил поиск профиля. Попробуйте позже.", "instagram")
            val data = root.optJSONObject("data")
            val key = if (reels) "xdt_api__v1__clips__user__connection_v2"
                else "xdt_api__v1__feed__user_timeline_graphql_connection"
            val connection = data?.optJSONObject(key)
                ?: throw InstagramProfileFormatException("Instagram изменил ответ поиска")
            val edges = connection.optJSONArray("edges")
                ?: throw InstagramProfileFormatException("Instagram изменил ответ поиска")
            val posts = buildList {
                for (index in 0 until edges.length()) {
                    val wrapper = edges.optJSONObject(index)?.optJSONObject("node") ?: continue
                    val node = wrapper.optJSONObject("media") ?: wrapper
                    val code = node.optString("code").ifBlank { node.optString("shortcode") }
                    if (!code.matches(Regex("[A-Za-z0-9_-]{5,64}"))) continue
                    val timestamp = node.optLong("taken_at").takeIf { it > 0 }
                        ?: node.optLong("taken_at_timestamp")
                    val preview = node.optString("thumbnail_url").ifBlank {
                        node.optString("display_url").ifBlank {
                            node.optJSONObject("image_versions2")?.optJSONArray("candidates")
                                ?.optJSONObject(0)?.optString("url").orEmpty()
                        }
                    }.takeIf { it.startsWith("https://") }
                    val count = node.optJSONArray("carousel_media")?.length()
                        ?: node.optJSONObject("edge_sidecar_to_children")
                            ?.optJSONArray("edges")?.length() ?: 1
                    add(InstagramProfilePost(code, timestamp,
                        node.optInt("media_type") == 2 || node.optBoolean("is_video") ||
                            node.optString("product_type") == "clips", preview, count.coerceAtLeast(1), reels))
                }
            }.distinctBy { it.code }
            val pageInfo = connection.optJSONObject("page_info")
                ?: throw InstagramProfileFormatException("Instagram изменил ответ поиска")
            val next = if (pageInfo.optBoolean("has_next_page"))
                pageInfo.optString("end_cursor").ifBlank {
                    throw InstagramProfileFormatException("Instagram не вернул страницу продолжения")
                } else null
            return InstagramProfilePage(posts, next)
        }

        fun parsePrivatePage(body: String, reels: Boolean = false): InstagramProfilePage {
            val root = runCatching { JSONObject(body) }.getOrNull()
                ?: throw InstagramProfileFormatException("Instagram изменил ответ поиска")
            val items = root.optJSONArray("items")
                ?: throw InstagramProfileFormatException("Instagram изменил ответ поиска")
            val posts = (0 until items.length()).mapNotNull { index ->
                val wrapper = items.optJSONObject(index) ?: return@mapNotNull null
                val node = wrapper.optJSONObject("media") ?: wrapper
                val code = node.optString("code")
                if (!code.matches(Regex("[A-Za-z0-9_-]{5,64}"))) return@mapNotNull null
                val preview = node.optString("thumbnail_url").ifBlank {
                    node.optJSONObject("image_versions2")?.optJSONArray("candidates")
                        ?.optJSONObject(0)?.optString("url").orEmpty()
                }.takeIf { it.startsWith("https://") }
                InstagramProfilePost(code, node.optLong("taken_at"),
                    node.optInt("media_type") == 2 || node.optBoolean("is_video"), preview,
                    (node.optJSONArray("carousel_media")?.length() ?: 1).coerceAtLeast(1), reels)
            }.distinctBy { it.code }
            val next = if (root.optBoolean("more_available"))
                root.optString("next_max_id").ifBlank {
                    throw InstagramProfileFormatException("Instagram не вернул страницу продолжения")
                }.let { "api:$it" } else null
            return InstagramProfilePage(posts, next)
        }

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}

private suspend fun requestProfile(method: String, url: String, body: String?, cookies: String,
    referer: String): String = withContext(Dispatchers.IO) {
    if (method == "GET" && url.contains("/api/"))
        return@withContext fetchInstagramApi(url, cookies)
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/142.0.0.0 Safari/537.36")
        if (cookies.isNotBlank()) connection.setRequestProperty("Cookie", cookies)
        connection.setRequestProperty("X-IG-App-ID", "936619743392459")
        connection.setRequestProperty("X-ASBD-ID", "359341")
        connection.setRequestProperty("X-IG-WWW-Claim", "0")
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        connection.setRequestProperty("Origin", "https://www.instagram.com")
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Accept", if (method == "GET") "text/html" else "*/*")
        if (method == "GET") {
            connection.setRequestProperty("Sec-Fetch-Dest", "document")
            connection.setRequestProperty("Sec-Fetch-Mode", "navigate")
            connection.setRequestProperty("Sec-Fetch-Site", "none")
        } else connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        Regex("csrftoken=([^;]+)").find(cookies)?.groupValues?.get(1)
            ?.let { connection.setRequestProperty("X-CSRFToken", it) }
        if (method == "POST") {
            connection.doOutput = true
            connection.outputStream.use { it.write(requireNotNull(body).toByteArray(Charsets.UTF_8)) }
        }
        when (val status = connection.responseCode) {
            in 200..299 -> connection.inputStream.bufferedReader().use { it.readText().take(6_000_000) }
            401, 302 -> {
                AppLog.i("InstagramProfile", "Web feed HTTP $status · redirect=${connection.getHeaderField("Location")?.substringBefore('?').orEmpty()}")
                throw ProviderException(ProviderException.Reason.ACCESS_REQUIRED,
                "Сессия Instagram недействительна. Войдите снова.", "instagram")
            }
            403 -> throw ProviderException(ProviderException.Reason.TEMPORARY_FAILURE,
                "Instagram отказал в доступе к веб-запросу HTTP 403", "instagram")
            429 -> throw ProviderException(ProviderException.Reason.RATE_LIMITED,
                "Instagram ограничил поиск профиля. Попробуйте позже.", "instagram")
            else -> throw ProviderException(ProviderException.Reason.TEMPORARY_FAILURE,
                "Instagram ответил HTTP $status", "instagram")
        }
    } finally { connection.disconnect() }
}
