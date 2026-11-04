package com.domenota.medialoader.data.gapi

import com.domenota.medialoader.core.provider.*
import kotlinx.coroutines.delay

/** Ordinary VK links use GAPI first. Legacy extraction remains local to a failed resolve. */
class GapiVkProvider(
    legacy: MediaProvider,
    keys: GapiKeyStore,
    client: GapiVkClient = GapiVkClient(),
    networkAvailable: () -> Boolean = { true },
    wait: suspend (Long) -> Unit = { delay(it) },
    now: () -> Long = System::currentTimeMillis,
    coordinator: GapiRequestCoordinator = GapiRequestCoordinator(),
) : MediaProvider {
    private val provider = GapiMediaProvider(legacy, keys, client::resolve, "VK", coordinator, networkAvailable, wait, now)
    override val id = VkProvider.ID
    override fun supports(url: String) = provider.supports(url)
    override fun safeDownloadUrl(url: String) = VkDirectMedia.isSafeUrl(url) || VkLinkParser.parse(url) != null
    override suspend fun resolve(url: String) = provider.resolve(url)
}
