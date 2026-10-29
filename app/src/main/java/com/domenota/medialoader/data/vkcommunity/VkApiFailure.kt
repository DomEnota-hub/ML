package com.domenota.medialoader.data.vkcommunity

class VkApiFailure(
    val kind: Kind,
    private val apiCode: Int? = null,
    private val httpStatus: Int? = null,
    private val method: String? = null,
) : Exception(kind.name) {
    enum class Kind { BAD_TOKEN, PERMISSION, RATE_LIMIT, PRIVATE_WALL, NOT_COMMUNITY, EMPTY_WALL, NETWORK, SERVER, OTHER }

    fun userMessage(): String = when (kind) {
        Kind.BAD_TOKEN -> "Ключ VK недействителен или отозван"
        Kind.PERMISSION -> "У ключа недостаточно прав для этой стены"
        Kind.RATE_LIMIT -> "VK ограничил частоту запросов. Повторите позже"
        Kind.PRIVATE_WALL -> "Стена сообщества закрыта или недоступна"
        Kind.NOT_COMMUNITY -> "Адрес ведёт не на сообщество VK"
        Kind.EMPTY_WALL -> "На стене нет публикаций; тип сообщества подтвердить не удалось"
        Kind.NETWORK -> "Не удалось соединиться с VK"
        Kind.SERVER -> "VK временно не отвечает"
        Kind.OTHER -> if (apiCode != null) "VK вернул ошибку $apiCode при поиске"
            else "VK вернул ошибку при поиске"
    }

    /** Numeric status and allowlisted method only; never include the token or VK response body. */
    fun diagnostic(): String = listOfNotNull(
        apiCode?.let { "api_code=$it" },
        httpStatus?.let { "http_status=$it" },
        method?.let { "method=$it" },
    ).joinToString(" ").ifBlank { "code=unavailable" }

    companion object {
        fun fromCode(code: Int, method: String? = null): VkApiFailure = VkApiFailure(when (code) {
            5 -> Kind.BAD_TOKEN
            6, 9, 29 -> Kind.RATE_LIMIT
            15, 7, 27 -> Kind.PERMISSION
            18, 30, 200 -> Kind.PRIVATE_WALL
            else -> Kind.OTHER
        }, apiCode = code, method = method)
        fun fromHttp(status: Int, method: String? = null): VkApiFailure = VkApiFailure(when (status) {
            401 -> Kind.BAD_TOKEN
            403 -> Kind.PERMISSION
            429 -> Kind.RATE_LIMIT
            in 500..599 -> Kind.SERVER
            else -> Kind.OTHER
        }, httpStatus = status, method = method)
    }
}
