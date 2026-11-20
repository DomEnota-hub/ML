package com.domenota.medialoader.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DuplicateMainThreadReadTest {
    @Test fun generationAndAdmissionDoNotWaitForLongSessionPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val epoch = DuplicatePrivacyGuard.generation(context)
        val journal = MusicDuplicateSessionStore.journalGeneration()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            synchronized(DuplicatePrivacyGuard.lock) {
                inside.countDown()
                release.await(4, TimeUnit.SECONDS)
            }
        }
        worker.start()
        try {
            assertTrue("Failed to acquire the simulated persistent report lock", inside.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            assertEquals(journal, MusicDuplicateSessionStore.journalGeneration())
            assertEquals(epoch, DuplicatePrivacyGuard.generation(context))
            assertTrue(DuplicatePrivacyGuard.accepts(context, epoch, "still-public-test-media"))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("Read-only UI checks waited for report IO: $elapsedMs ms", elapsedMs < 800)
        } finally {
            release.countDown()
            worker.join(1500)
        }
    }

    @Test fun epochSnapshotPreservesRevokedIdsAndAliases() {
        val epoch = DuplicatePrivacyEpoch(17)
        val identities = setOf("alias-test")
        epoch.revoke("private-test", identities)
        assertFalse(epoch.accepts(17, "private-test"))
        assertFalse(epoch.accepts(18, "other", identities))
        assertEquals(setOf("private-test"), epoch.blockedIdsSnapshot())
        assertEquals(identities, epoch.blockedAliasesSnapshot())
        epoch.restored("private-test")
        assertTrue(epoch.blockedIdsSnapshot().isEmpty())
        assertTrue(epoch.blockedAliasesSnapshot().isEmpty())
    }
}
