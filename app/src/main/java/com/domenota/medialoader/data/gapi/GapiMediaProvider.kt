package com.domenota.medialoader.data.gapi

import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.provider.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes API calls across Instagram and VK when they share a key pool. */
class GapiRequestCoordinator {
    internal val gate = Mutex()
    internal var lastCallMillis = 0L
}

/** Every new resolve starts with GAPI; legacy fallback never changes the primary route. */
internal class GapiMediaProvider(
    private val legacy: MediaProvider,
    private val keys: GapiKeyStore,
    private val resolveGapi: suspend (String, String) -> List<MediaItem>,
    private val label: String,
    private val coordinator: GapiRequestCoordinator,
    private val networkAvailable: () -> Boolean = { true },
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) : MediaProvider {
    override val id = legacy.id
    override fun supports(url: String) = legacy.supports(url)
    override fun safeDownloadUrl(url: String) = legacy.safeDownloadUrl(url)

    override suspend fun resolve(url: String): List<MediaItem> = withContext(Dispatchers.IO) {
        coordinator.gate.withLock {
            if (!networkAvailable()) throw ProviderException(ProviderException.Reason.TEMPORARY_FAILURE,
                "Нет соединения. Повторите позже или добавьте ссылку в отложенное.", id)
            var available = keys.eligible()
            if (available.isEmpty()) {
                val recovery = keys.nextAvailableAtMillis()?.minus(now())
                if (recovery != null && recovery in 1..5_000L) {
                    wait(recovery)
                    available = keys.eligible()
                }
            }
            var failure: GapiFailureKind? = null
            keyLoop@ for (key in available) {
                var retried = false
                while (true) {
                    // Builtin activation and user choices may change during an earlier request.
                    if (keys.eligible().none { it.id == key.id }) continue@keyLoop
                    val spacing = (1_100L - (now() - coordinator.lastCallMillis)).coerceAtMost(1_100L)
                    if (spacing > 0) wait(spacing)
                    coordinator.lastCallMillis = now()
                    try {
                        val media = resolveGapi(url, key.token)
                        if (media.isEmpty()) throw GapiException(GapiFailureKind.NOT_FOUND)
                        keys.markSuccess(key.id)
                        AppLog.i(label, "Media resolved · method=gapi · items=${media.size}")
                        return@withLock media
                    } catch (e: CancellationException) { throw e }
                    catch (e: GapiException) {
                        failure = e.kind
                        AppLog.w(label, "GAPI unavailable · reason=${e.kind}")
                        when (e.kind) {
                            GapiFailureKind.QUOTA_EXHAUSTED -> { keys.markExhausted(key.id); continue@keyLoop }
                            GapiFailureKind.INVALID_KEY -> { keys.markInvalid(key.id); continue@keyLoop }
                            GapiFailureKind.RATE_LIMITED -> {
                                val retry = (e.retryAfterMillis ?: 2_000L).coerceAtLeast(1_100L)
                                keys.pause(key.id, now() + retry)
                                if (!retried && retry <= 5_000L) {
                                    retried = true; wait(retry); continue
                                }
                            }
                            GapiFailureKind.TEMPORARY -> if (!retried) {
                                retried = true; wait(1_500L); continue
                            }
                            else -> Unit
                        }
                        break@keyLoop
                    }
                }
            }
            if (keys.fallbackEnabled) {
                AppLog.i(label, "Using existing extractor for this request · method=legacy-fallback")
                legacy.resolve(url)
            } else {
                throw ProviderException(
                    if (failure == GapiFailureKind.RATE_LIMITED) ProviderException.Reason.RATE_LIMITED
                    else ProviderException.Reason.TEMPORARY_FAILURE,
                    if (available.isEmpty()) "Нет доступных ключей GAPI. Проверьте ключи и их лимиты в настройках."
                    else "GAPI не смог получить медиа. Резервный способ отключён в настройках.", id,
                )
            }
        }
    }
}
