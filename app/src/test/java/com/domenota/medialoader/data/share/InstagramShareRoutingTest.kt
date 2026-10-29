package com.domenota.medialoader.data.share

import android.content.Context
import android.content.Intent
import com.domenota.medialoader.MainActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstagramShareRoutingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun share(url: String = "https://instagram.com/p/C7YnGRuqHhA/?igsh=test") =
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    @Before fun setup() {
        InstagramShareSettings.setAutomatic(context, true)
        context.getSharedPreferences("module_archive", 0).edit().putBoolean("instagram_profile_disabled", true).commit()
    }
    @Test fun coldExplicitMainShareForwardsBeforeOpeningUi() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, share()).create()
        val activity = controller.get()
        try {
            assertEquals(InstagramShareActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            assertTrue(activity.isFinishing)
            assertEquals(Intent.ACTION_MAIN, activity.intent.action)
        } finally { controller.destroy() }
    }
    @Test fun newIntentUsesAutomaticReceiverAndDoesNotFinishExistingMain() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            controller.newIntent(share())
            val activity = controller.get()
            val redirected = shadowOf(activity).nextStartedActivity
            assertEquals(InstagramShareActivity::class.java.name, redirected.component?.className)
            assertTrue(redirected.getBooleanExtra(InstagramShareRouting.RETURN_TO_SOURCE, false))
            assertFalse(activity.isFinishing)
            assertEquals(Intent.ACTION_MAIN, activity.intent.action)
        } finally { controller.destroy() }
    }
    @Test fun manualOtherSourcesAndLauncherAreNotAutomatic() {
        assertNotNull(InstagramShareRouting.automaticUrl(context, share()))
        assertNull(InstagramShareRouting.automaticUrl(context, share("https://youtube.com/watch?v=test")))
        assertNull(InstagramShareRouting.automaticUrl(context, Intent(Intent.ACTION_MAIN)))
        InstagramShareSettings.setAutomatic(context, false)
        assertNull(InstagramShareRouting.automaticUrl(context, share()))
    }
}
