package com.domenota.medialoader.data.catalog.visibility

/** Clock-only transfer timing. Never log a URI, path, file name or proof bytes. */
internal fun visibilityDurationMs(startNs: Long, endNs: Long): Long =
    if (endNs < startNs) 0L else (endNs - startNs) / 1_000_000L
