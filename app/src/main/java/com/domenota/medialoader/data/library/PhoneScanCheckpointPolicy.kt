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
