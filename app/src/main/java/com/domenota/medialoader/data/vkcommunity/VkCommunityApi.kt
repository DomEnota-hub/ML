package com.domenota.medialoader.data.vkcommunity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** VK API calls only. The service token is sent in the POST body, never in a URL or log. */
class VkCommunityApi {
    suspend fun communityInfo(link: VkCommunityLink, token: String): VkCommunityInfo {
        val identifier = when (link) {
            is VkCommunityLink.Owner -> (-link.id).toString()
            is VkCommunityLink.Domain -> link.name
        }
        val response = call("groups.getById", mapOf("group_ids" to identifier,
            "fields" to "photo_100,photo_200"), token).get("response")
        return parseCommunityInfo(response)
            ?: throw VkApiFailure(VkApiFailure.Kind.OTHER, method = "groups.getById")
    }

    /** One request can fill avatars for existing shortcuts without a request per row. */
    suspend fun communityInfos(links: List<VkCommunityLink>, token: String): Map<String, VkCommunityInfo> {
        if (links.isEmpty()) return emptyMap()
        val identifiers = links.take(100).joinToString(",") { link -> when (link) {
            is VkCommunityLink.Owner -> (-link.id).toString()
            is VkCommunityLink.Domain -> link.name
        } }
        val response = call("groups.getById", mapOf("group_ids" to identifiers,
            "fields" to "photo_100,photo_200"), token).get("response")
        val groups = responseGroups(response) ?: return emptyMap()
        return buildMap {
            for (index in 0 until groups.length()) {
                val group = groups.optJSONObject(index) ?: continue
                val info = parseCommunityInfo(JSONArray().put(group)) ?: continue
                val id = group.optLong("id")
                if (id > 0) put("owner:-$id", info)
                val domain = group.optString("screen_name").trim().lowercase(Locale.ROOT)
                if (domain.isNotBlank()) put("domain:$domain", info)
            }
        }
    }

    suspend fun wallPage(link: VkCommunityLink, offset: Int, token: String): JSONObject {
        require(offset >= 0)
        val address = when (link) {
            is VkCommunityLink.Owner -> mapOf("owner_id" to link.id.toString())
            is VkCommunityLink.Domain -> mapOf("domain" to link.name)
        }
        val response = call("wall.get", address + mapOf("count" to "100", "offset" to offset.toString()), token)
            .getJSONObject("response")
        verifyCommunityWall(link, response, offset)
        return response
    }

    private suspend fun call(method: String, params: Map<String, String>, token: String): JSONObject =
        withContext(Dispatchers.IO) {
            require(method == "wall.get" || method == "groups.getById")
            val connection = try {
                URL("https://api.vk.com/method/$method").openConnection() as HttpURLConnection
            } catch (_: Exception) {
                throw VkApiFailure(VkApiFailure.Kind.NETWORK)
            }
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                val body = (params + mapOf("access_token" to token, "v" to "5.199"))
                    .entries.joinToString("&") { (key, value) ->
                        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                    }.toByteArray(Charsets.UTF_8)
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                if (status !in 200..299) throw VkApiFailure.fromHttp(status, method)
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        if (output.size() + read > 8_000_000) throw IOException("VK response too large")
                        output.write(chunk, 0, read)
                    }
                    output.toByteArray()
                }
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                json.optJSONObject("error")?.let { throw VkApiFailure.fromCode(it.optInt("error_code"), method) }
                if (!json.has("response")) throw VkApiFailure(VkApiFailure.Kind.OTHER, method = method)
                json
            } catch (error: VkApiFailure) {
                throw error
            } catch (_: Exception) {
                throw VkApiFailure(VkApiFailure.Kind.NETWORK)
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        private fun responseGroups(response: Any): JSONArray? = when (response) {
            is JSONArray -> response
            is JSONObject -> response.optJSONArray("groups")
            else -> null
        }

        internal fun parseCommunityInfo(response: Any): VkCommunityInfo? {
            val groups = responseGroups(response) ?: return null
            val group = groups.optJSONObject(0) ?: return null
            val name = group.optString("name").trim().take(80)
            if (name.isBlank()) return null
            val avatar = listOf("photo_200", "photo_100")
                .map { group.optString(it) }
                .firstOrNull { it.startsWith("https://") &&
                    com.domenota.medialoader.data.preview.VkThumbnailPolicy.isAllowedUrl(it) }
            return VkCommunityInfo(name, avatar)
        }

        internal fun verifyCommunityWall(link: VkCommunityLink, response: JSONObject, offset: Int) {
            val posts = response.optJSONArray("items") ?: throw VkApiFailure(VkApiFailure.Kind.OTHER)
            if (link is VkCommunityLink.Domain && offset == 0 && posts.length() == 0)
                throw VkApiFailure(VkApiFailure.Kind.EMPTY_WALL)
            for (index in 0 until posts.length()) {
                val owner = posts.optJSONObject(index)?.optLong("owner_id") ?: 0L
                if (owner >= 0 || link is VkCommunityLink.Owner && owner != link.id)
                    throw VkApiFailure(VkApiFailure.Kind.NOT_COMMUNITY)
            }
        }
    }
}

data class VkCommunityInfo(val name: String, val avatarUrl: String?)
