package com.domenota.medialoader.data.share

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.ui.extractFirstUrl

/**
 * Dedicated, transparent ACTION_SEND receiver. Never opens the main UI when
 * automatic Instagram share mode is enabled; all other shares keep the
 * existing interactive picker through MainActivity.
 */
class InstagramShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
        val text = intent?.takeIf {
            it.action == Intent.ACTION_SEND && it.type == "text/plain"
        }?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val link = extractFirstUrl(text)
        val canonical = InstagramShareRouting.automaticUrl(this, intent)
        AppLog.i("Share", "Share route · entry=receiver · automatic=${InstagramShareSettings.isAutomatic(this)} · supported=${link?.let(InstagramAutoShareSelection::canonicalUrl) != null}")

        if (InstagramShareSettings.isAutomatic(this) && canonical != null) {
            try {
                val operation = InstagramAutoShareWorker.schedule(applicationContext, canonical)
                // Wait for WorkManager to commit the task before closing the short
                // receiver Activity. Otherwise Android may kill the process first.
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { operation.result.get() }
                        AppLog.ok("Share", "Instagram visual auto-download scheduled")
                    } catch (error: Exception) {
                        AppLog.w("Share", "Could not persist shared download", error)
                        Toast.makeText(this@InstagramShareActivity, "Не удалось добавить загрузку",
                            Toast.LENGTH_LONG).show()
                    } finally {
                        if (intent.getBooleanExtra(InstagramShareRouting.RETURN_TO_SOURCE, false)) moveTaskToBack(true)
                        finish()
                    }
                }
                return
            } catch (error: Exception) {
                AppLog.w("Share", "Cannot schedule Instagram share", error)
                Toast.makeText(this, "Не удалось добавить загрузку", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        } else {
            // Preserve ACTION_SEND semantics, including non-Instagram shares,
            // without routing back through this receiver.
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        finish()
    }
}
