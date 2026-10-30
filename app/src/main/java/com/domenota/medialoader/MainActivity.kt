package com.domenota.medialoader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.data.instagramprofile.InstagramProfileController
import com.domenota.medialoader.ui.MediaLoaderApp
import com.domenota.medialoader.data.playback.AudioWaveformPrecompute
import com.domenota.medialoader.ui.theme.MediaLoaderTheme
import com.domenota.medialoader.data.share.InstagramShareActivity
import com.domenota.medialoader.data.share.InstagramShareRouting

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedTextGeneration by mutableIntStateOf(0)
    private var wasForeground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLog.init(applicationContext)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && forwardAutomaticShare(intent, false)) {
            finish()
            return
        }
        // Share forwarding must run before scheduling background work.
        runCatching { AudioWaveformPrecompute.schedule(applicationContext) }
            .onFailure { AppLog.w("AudioWaveform", "Background scan deferred: ${it.javaClass.simpleName}") }
        com.domenota.medialoader.ui.theme.edgeToEdgeWindow(window)
        val archivePrefs = getSharedPreferences("module_archive", MODE_PRIVATE)
        if (!archivePrefs.getBoolean("instagram_profile_disabled", false)) {
            val controller = InstagramProfileController(applicationContext)
            controller.store.jobs().forEach { controller.cancel(it.id) }
            archivePrefs.edit().putBoolean("instagram_profile_disabled", true).apply()
        }
        // A recreated activity (rotation) must not re-import the link the user already saw.
        if (savedInstanceState == null) {
            textFrom(intent)?.let {
                sharedText = it
                sharedTextGeneration++
            }
        }
        setContent {
            MediaLoaderTheme {
                MediaLoaderApp(
                    sharedText = sharedText,
                    sharedTextGeneration = sharedTextGeneration,
                    onSharedTextConsumed = { sharedText = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (forwardAutomaticShare(intent, !wasForeground)) return
        textFrom(intent)?.let {
            sharedText = it
            sharedTextGeneration++
            AppLog.i("App", "Received shared text intent · generation=$sharedTextGeneration")
        }
    }

    override fun onResume() { super.onResume(); wasForeground = true }
    override fun onStop() { wasForeground = false; super.onStop() }

    private fun forwardAutomaticShare(incoming: Intent?, returnToSource: Boolean): Boolean {
        if (InstagramShareRouting.automaticUrl(this, incoming) == null) return false
        AppLog.i("Share", "Automatic route · entry=MainActivity · returnToSource=$returnToSource")
        startActivity(Intent(this, InstagramShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, incoming?.getStringExtra(Intent.EXTRA_TEXT))
            putExtra(InstagramShareRouting.RETURN_TO_SOURCE, returnToSource)
        })
        // Recreating the existing main window must not import or schedule this intent again.
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        return true
    }

    private fun textFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)
}
