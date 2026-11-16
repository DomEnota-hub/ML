package com.domenota.medialoader.data.playback

import android.os.Looper
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioControllerConnectionTest {
    @Test fun miniAndFullPlayerSharePendingConnectionAndLastLeaseOwnsRelease() {
        val future = SettableFuture.create<MediaController>()
        var builds = 0
        val connections = AudioControllerConnections { builds++; future }
        val context = RuntimeEnvironment.getApplication()
        val mini = connections.acquire(context)
        val full = connections.acquire(context)
        assertSame(mini.future, full.future)
        assertEquals(1, builds)
        mini.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertFalse(future.isCancelled)
        full.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(future.isCancelled)
    }
    @Test fun reentryDuringGraceRetainsConnectionAndReleaseIsIdempotent() {
        val futures = mutableListOf<SettableFuture<MediaController>>()
        val connections = AudioControllerConnections {
            SettableFuture.create<MediaController>().also { futures += it }
        }
        val context = RuntimeEnvironment.getApplication()
        val first = connections.acquire(context)
        first.release(); first.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        val recreated = connections.acquire(context)
        assertSame(first.future, recreated.future)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(first.future.isCancelled)
        recreated.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(first.future.isCancelled)
        val next = connections.acquire(context)
        assertNotSame(first.future, next.future)
        assertEquals(2, futures.size)
        next.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }
}
