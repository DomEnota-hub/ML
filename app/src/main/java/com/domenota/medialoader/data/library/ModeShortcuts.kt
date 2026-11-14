package com.domenota.medialoader.data.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.R

internal object ModeShortcuts {
    private const val GALLERY = "com.domenota.medialoader.OPEN_GALLERY"
    private const val MUSIC = "com.domenota.medialoader.OPEN_MUSIC"
    fun mode(action: String?): AppMode? = when (action) { GALLERY, VisualDuplicateBackgroundScan.OPEN_ACTION -> AppMode.GALLERY; MUSIC, DuplicateBackgroundScan.OPEN_ACTION -> AppMode.MUSIC; else -> null }
    fun id(mode: AppMode): String = when (mode) { AppMode.GALLERY -> "ml_gallery"; AppMode.MUSIC -> "ml_music"; else -> error("Unsupported mode") }
    fun supported(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)
    fun pinned(context: Context): Set<String> = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }.toSet()
    private fun preferences(context: Context) = context.getSharedPreferences("mode_shortcuts", Context.MODE_PRIVATE)
    fun branded(context: Context): Boolean = preferences(context).getBoolean("branded_icons", true)
    fun setBranded(context: Context, enabled: Boolean): Boolean {
        preferences(context).edit().putBoolean("branded_icons", enabled).apply()
        val existing = pinned(context)
        val updates = listOf(AppMode.GALLERY, AppMode.MUSIC).filter { id(it) in existing }.map { shortcut(context, it) }
        return updates.isEmpty() || ShortcutManagerCompat.updateShortcuts(context, updates)
    }
    private fun shortcut(context: Context, mode: AppMode): ShortcutInfoCompat {
        val action = when (mode) { AppMode.GALLERY -> GALLERY; AppMode.MUSIC -> MUSIC; else -> error("Unsupported mode") }
        val label = if (mode == AppMode.GALLERY) "Галерея" else "Музыка"
        val launch = Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val shortcutIcon = if (branded(context)) {
            IconCompat.createWithResource(context, if (mode == AppMode.GALLERY) R.drawable.ml_shortcut_gallery else R.drawable.ml_shortcut_music)
        } else IconCompat.createWithAdaptiveBitmap(icon(mode))
        return ShortcutInfoCompat.Builder(context, id(mode)).setShortLabel(label).setLongLabel(label)
            .setIntent(launch).setIcon(shortcutIcon).build()
    }
    fun request(context: Context, mode: AppMode): Boolean {
        if (mode != AppMode.GALLERY && mode != AppMode.MUSIC || !supported(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut(context, mode), null)
    }
    private fun icon(mode: AppMode): Bitmap {
        val bitmap = Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (mode == AppMode.GALLERY) Color.rgb(43,86,125) else Color.rgb(91,53,156))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        if (mode == AppMode.GALLERY) {
            paint.style = Paint.Style.STROKE
            canvas.drawRoundRect(RectF(53f,55f,139f,137f),10f,10f,paint)
            paint.style = Paint.Style.FILL; canvas.drawCircle(115f,78f,7f,paint)
            val path = android.graphics.Path().apply { moveTo(60f,125f); lineTo(86f,95f); lineTo(103f,111f); lineTo(114f,101f); lineTo(132f,125f); close() }
            canvas.drawPath(path,paint)
        } else {
            canvas.drawOval(RectF(65f,112f,97f,137f),paint)
            canvas.drawRect(89f,57f,97f,125f,paint)
            canvas.drawRect(93f,57f,129f,69f,paint)
            canvas.drawRect(121f,62f,129f,101f,paint)
            canvas.drawOval(RectF(97f,91f,129f,116f),paint)
        }
        return bitmap
    }
}
