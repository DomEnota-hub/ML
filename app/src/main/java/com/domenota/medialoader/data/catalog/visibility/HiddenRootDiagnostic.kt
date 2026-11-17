package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Read-only diagnostics of .nomedia. Never logs a user path, filename or file content.
 * The probe is called only after the existing safety gate has already failed.
 */
internal enum class HiddenRootMarkerCode {
    VALID, MISSING, SYMBOLIC_LINK, DIRECTORY, NON_REGULAR, NONEMPTY, ACCESS_DENIED, UNKNOWN
}

internal data class HiddenRootMarkerObservation(
    val code: HiddenRootMarkerCode,
    val sizeBytes: Long? = null,
) {
    /** Safe, fixed labels; intentionally no File or exception message. */
    fun extendedSummary(): String = "markerType=$code markerBytes=${sizeBytes ?: "unknown"}"
}

internal object HiddenRootDiagnostic {
    fun observeNomedia(marker: File): HiddenRootMarkerObservation = try {
        val info = Files.readAttributes(marker.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        when {
            info.isSymbolicLink -> HiddenRootMarkerObservation(HiddenRootMarkerCode.SYMBOLIC_LINK)
            info.isDirectory -> HiddenRootMarkerObservation(HiddenRootMarkerCode.DIRECTORY)
            !info.isRegularFile -> HiddenRootMarkerObservation(HiddenRootMarkerCode.NON_REGULAR)
            info.size() != 0L -> HiddenRootMarkerObservation(HiddenRootMarkerCode.NONEMPTY, info.size())
            else -> HiddenRootMarkerObservation(HiddenRootMarkerCode.VALID, 0L)
        }
    } catch (_: NoSuchFileException) {
        HiddenRootMarkerObservation(HiddenRootMarkerCode.MISSING)
    } catch (_: AccessDeniedException) {
        HiddenRootMarkerObservation(HiddenRootMarkerCode.ACCESS_DENIED)
    } catch (_: Exception) {
        HiddenRootMarkerObservation(HiddenRootMarkerCode.UNKNOWN)
    }
}
