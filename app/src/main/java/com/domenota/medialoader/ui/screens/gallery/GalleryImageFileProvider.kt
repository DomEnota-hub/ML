package com.domenota.medialoader.ui.screens.gallery

import android.net.Uri
import androidx.core.content.FileProvider
import java.util.Locale

/**
 * Explicit image MIME types for wallpaper/preview exports.
 *
 * Android WallpaperManager may reject content URIs when a provider returns
 * application/octet-stream, even when the file contains a valid PNG or JPEG.
 * FileProvider's MIME inference is device/framework dependent; use the
 * extensions of our private cache exports for deterministic content types.
 */
class GalleryImageFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val filename = uri.lastPathSegment?.lowercase(Locale.ROOT).orEmpty()
        return when {
            filename.endsWith(".png") -> "image/png"
            filename.endsWith(".jpg") || filename.endsWith(".jpeg") -> "image/jpeg"
            else -> super.getType(uri)
        }
    }
}
