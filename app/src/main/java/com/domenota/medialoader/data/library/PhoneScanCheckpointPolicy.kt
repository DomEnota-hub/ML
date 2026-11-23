package com.domenota.medialoader.data.library

/**
 * A MediaStore scan should not synchronously serialize a growing 10k+ row
 * snapshot after every UI progress update. Save the first useful page, then
 * occasional restart checkpoints; PhoneLibraryCache.write is still called for
 * the complete snapshot after the scan finishes.
 */
internal class PhoneScanCheckpointPolicy(
    private val firstPageSize: Int = 64,
    private val interval: Int = 2048,
) {
    init {
        require(firstPageSize > 0)
        require(interval > 0)
    }

    private var lastSavedCount = 0

    fun shouldSave(scannedRows: Int): Boolean {
        if (scannedRows < firstPageSize) return false
        if (lastSavedCount == 0 || scannedRows - lastSavedCount >= interval) {
            lastSavedCount = scannedRows
            return true
        }
        return false
    }
}

/** Throttle incremental Gallery/Audio UI snapshots independently of disk checkpoints.
 * Returning 11k partially indexed rows every second restarts composition and
 * background projections repeatedly on low-memory phones. The first real page
 * is still delivered immediately; the complete validated snapshot is untouched.
 * Call only after the current access/visibility guard has been verified.
 */
internal class PhoneScanProgressPolicy(
    private val firstPageSize: Int = 64,
    private val minRowsSincePublication: Int = 512,
    private val minIntervalMs: Long = 3_000L,
) {
    init { require(firstPageSize > 0 && minRowsSincePublication > 0 && minIntervalMs >= 0) }
    private var lastRows = 0
    private var lastAtMs = 0L

    fun shouldPublish(rows: Int, nowMs: Long): Boolean {
        if (rows < firstPageSize) return false
        if (lastRows != 0 &&
            (rows - lastRows < minRowsSincePublication || nowMs - lastAtMs < minIntervalMs)
        ) return false
        lastRows = rows
        lastAtMs = nowMs
        return true
    }
}
