package com.domenota.medialoader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.logging.ProcessExitDiagnostics
import com.domenota.medialoader.data.instagramprofile.InstagramProfileController
import com.domenota.medialoader.ui.MediaLoaderApp
import com.domenota.medialoader.data.playback.AudioWaveformPrecompute
import com.domenota.medialoader.ui.theme.MediaLoaderTheme
import com.domenota.medialoader.data.share.InstagramShareActivity
import com.domenota.medialoader.data.share.InstagramShareRouting

class MainActivity : ComponentActivity() {
    private var currentPlayerRequests by mutableStateOf(com.domenota.medialoader.data.playback.CurrentPlayerRequestState())
    private fun acceptCurrentPlayer(incoming:Intent?) {
        val next=currentPlayerRequests.accept(incoming?.action)
        if(next!=currentPlayerRequests) {
            currentPlayerRequests=next
            // Consume the incoming action before any recreation; pending state is saved separately.
            setIntent(Intent(this,MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        }
    }
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedTextGeneration by mutableIntStateOf(0)
    private var wasForeground = false
    private var shortcutMode by mutableStateOf<com.domenota.medialoader.data.library.AppMode?>(null)
    private var shortcutGeneration by mutableIntStateOf(0)
    private var openDuplicates by mutableStateOf(false)
    private fun acceptModeShortcut(incoming: Intent?) {
        com.domenota.medialoader.data.library.ModeShortcuts.mode(incoming?.action)?.let {
            openDuplicates = incoming?.action == com.domenota.medialoader.data.library.DuplicateBackgroundScan.OPEN_ACTION || incoming?.action == com.domenota.medialoader.data.library.VisualDuplicateBackgroundScan.OPEN_ACTION
            shortcutMode = it; shortcutGeneration++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupAt = android.os.SystemClock.uptimeMillis()
        AppLog.init(applicationContext)
        com.domenota.medialoader.core.logging.PerformanceLog.begin("activity_create")
        super.onCreate(savedInstanceState)
        currentPlayerRequests=com.domenota.medialoader.data.playback.CurrentPlayerRequestState.restore(savedInstanceState)
        acceptCurrentPlayer(intent)
        lifecycleScope.launch(Dispatchers.IO) { ProcessExitDiagnostics.recordPreviousExits(applicationContext) }
        lifecycleScope.launch(Dispatchers.IO) {
            // Remove only the retired 1.7.182 derived index, never original media.
            com.domenota.medialoader.data.library.RetiredAlbumCleanup.run(applicationContext)
        }
        if (savedInstanceState == null && forwardAutomaticShare(intent, false)) {
            finish()
            return
        }
        com.domenota.medialoader.core.logging.PerformanceLog.start()
        com.domenota.medialoader.ui.theme.edgeToEdgeWindow(window)
        val archivePrefs = getSharedPreferences("module_archive", MODE_PRIVATE)
        if (!archivePrefs.getBoolean("instagram_profile_disabled", false)) {
            val controller = InstagramProfileController(applicationContext)
            controller.store.jobs().forEach { controller.cancel(it.id) }
            archivePrefs.edit().putBoolean("instagram_profile_disabled", true).apply()
        }
        // A recreated activity (rotation) must not re-import the link the user already saw.
        if (savedInstanceState == null) {
            acceptModeShortcut(intent)
            textFrom(intent)?.let {
                sharedText = it
                sharedTextGeneration++
            }
        }
        setContent {
            MediaLoaderTheme {
                MediaLoaderApp(
                    currentPlayerRequest = currentPlayerRequests.pending,
                    onCurrentPlayerRequestConsumed = { request -> currentPlayerRequests=currentPlayerRequests.consume(request) },
                    shortcutMode = shortcutMode,
                    shortcutGeneration = shortcutGeneration,
                    shortcutDuplicates = openDuplicates,
                    onShortcutConsumed = { shortcutMode = null },
                    sharedText = sharedText,
                    sharedTextGeneration = sharedTextGeneration,
                    onSharedTextConsumed = { sharedText = null },
                )
            }
        }
        window.decorView.doOnPreDraw {
            com.domenota.medialoader.core.logging.PerformanceLog.end("activity_first_pre_draw", startupAt)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { AudioWaveformPrecompute.schedule(applicationContext) }
                    .onFailure { AppLog.w("AudioWaveform", "Background scan deferred: ${it.javaClass.simpleName}") }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptCurrentPlayer(intent)
        if (forwardAutomaticShare(intent, !wasForeground)) return
        acceptModeShortcut(intent)
        textFrom(intent)?.let {
            sharedText = it
            sharedTextGeneration++
            AppLog.i("App", "Received shared text intent · generation=$sharedTextGeneration")
        }
    }

    override fun onSaveInstanceState(outState:Bundle) {
        currentPlayerRequests.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        com.domenota.medialoader.core.logging.PerformanceLog.start()
    }
    override fun onStop() {
        com.domenota.medialoader.core.logging.PerformanceLog.stop()
        wasForeground = false
        super.onStop()
    }

    override fun onResume() { super.onResume(); wasForeground = true }

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
