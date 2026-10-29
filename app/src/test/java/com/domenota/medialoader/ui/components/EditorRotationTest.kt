package com.domenota.medialoader.ui.components

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.domenota.medialoader.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorRotationTest {
    @Test fun rotationDoesNotRecreateActivityHoldingMediaEditorDrafts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activity = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        val required = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE
        assertEquals(required, activity.configChanges and required)
    }
}
