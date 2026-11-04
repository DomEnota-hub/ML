package com.domenota.medialoader.data.gapi

import com.domenota.medialoader.core.provider.*
import kotlinx.coroutines.delay

/** Every new resolve starts with GAPI; legacy fallback never changes the primary route. */
class GapiInstagramProvider(
    legacy: MediaProvider,
    keys: GapiKeyStore,
    client: GapiInstagramClient = GapiInstagramClient(),
    networkAvailable: () -> Boolean = { true },
    wait: suspend (Long) -> Unit = { delay(it) },
    now: () -> Long = System::currentTimeMillis,
    coordinator: GapiRequestCoordinator = GapiRequestCoordinator(),
) : MediaProvider by GapiMediaProvider(legacy, keys, client::resolve, "Instagram", coordinator, networkAvailable, wait, now)
