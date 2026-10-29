package com.domenota.medialoader.data.storage

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException

/** Read-only check. Never infer that a file was deleted from a permission failure. */
enum class SavedFileStatus { UNCHECKED, PRESENT, MISSING, INACCESSIBLE }

object SavedFilePresence {
    fun check(context: Context, savedUri: String?): SavedFileStatus {
        if (savedUri.isNullOrBlank()) return SavedFileStatus.MISSING
        val uri = runCatching { Uri.parse(savedUri) }.getOrNull()
            ?: return SavedFileStatus.INACCESSIBLE
        return when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return SavedFileStatus.INACCESSIBLE
                val file = File(path)
                when {
                    file.isFile -> SavedFileStatus.PRESENT
                    file.parentFile?.canRead() == true -> SavedFileStatus.MISSING
                    else -> SavedFileStatus.INACCESSIBLE
                }
            }
            "content" -> try {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return if (isLocalFileProvider(uri.authority)) SavedFileStatus.MISSING
                        else SavedFileStatus.INACCESSIBLE
                descriptor.use { SavedFileStatus.PRESENT }
            } catch (_: SecurityException) {
                SavedFileStatus.INACCESSIBLE
            } catch (error: FileNotFoundException) {
                // A missing local MediaStore/document provider entry is meaningful.
                // A provider outside the device may be offline: don't declare it deleted.
                if (error.message?.contains("permission", ignoreCase = true) == true)
                    SavedFileStatus.INACCESSIBLE
                else if (isLocalFileProvider(uri.authority)) SavedFileStatus.MISSING
                else SavedFileStatus.INACCESSIBLE
            } catch (_: Exception) {
                SavedFileStatus.INACCESSIBLE
            }
            else -> SavedFileStatus.INACCESSIBLE
        }
    }

    private fun isLocalFileProvider(authority: String?): Boolean =
        authority == MediaStore.AUTHORITY ||
            authority == "com.android.externalstorage.documents" ||
            authority == "com.android.providers.downloads.documents"
}
