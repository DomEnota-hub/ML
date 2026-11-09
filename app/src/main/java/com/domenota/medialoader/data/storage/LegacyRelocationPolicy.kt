package com.domenota.medialoader.data.storage

/**
 * A public MediaStore item is eligible for in-place relocation only when it
 * belongs to MediaLoader. Copy/delete changes DATE_ADDED, so it is forbidden.
 */
internal object LegacyRelocationPolicy {
    fun targetPath(
        sourceRelativePath: String?,
        ownerPackageName: String?,
        ourPackageName: String,
        savedName: String?,
        expectedName: String,
        sourceMime: String?,
        expectedMime: String,
        picturesPath: String,
        targetNameTaken: Boolean,
    ): String? {
        if (!LegacyMediaMigration.isLegacyPath(sourceRelativePath)) return null
        if (ownerPackageName.isNullOrBlank() || ownerPackageName != ourPackageName) return null
        if (savedName.isNullOrBlank() || savedName != expectedName) return null
        if (!sourceMime.equals(expectedMime, ignoreCase = true)) return null
        if (targetNameTaken) return null
        return MediaStoragePolicy.route(picturesPath, expectedMime)
            .takeIf { it != sourceRelativePath }
    }

    fun unchangedMetadata(
        beforeName: String,
        afterName: String?,
        beforeSize: Long,
        afterSize: Long?,
        beforeDateAddedSeconds: Long,
        afterDateAddedSeconds: Long?,
        beforeDateModifiedSeconds: Long,
        afterDateModifiedSeconds: Long?,
    ): Boolean =
        beforeName == afterName &&
            beforeSize > 0L && afterSize == beforeSize &&
            beforeDateAddedSeconds > 0L &&
            beforeDateAddedSeconds == afterDateAddedSeconds &&
            beforeDateModifiedSeconds > 0L &&
            beforeDateModifiedSeconds == afterDateModifiedSeconds
}
