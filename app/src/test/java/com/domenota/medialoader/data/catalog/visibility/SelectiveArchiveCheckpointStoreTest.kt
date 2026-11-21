package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SelectiveArchiveCheckpointStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private val physical=(1..806).map { NomediaMediaSignature("track-$it.mp3",100L+it) }
    private val selected=physical.take(6)
    private fun identities()=(1..6).map { index ->
        SelectiveArchiveIdentity(
            "catalog-$index","content://media/external/audio/media/$index",
            index.toLong(),"external_primary","Music/Shared/",
            "track-$index.mp3",100L+index,"a".repeat(64),
            1_700_000_000_000L+index,"b".repeat(64),
        )
    }
    @Test fun preparationIsDurableAcrossInstancesAndCannotOverwriteOldIdentity()=runBlocking {
        val dir=temp.newFolder("archive-proof")
        val a=SelectiveArchiveCheckpointStore(dir)
        val expected=identities()
        val proof=a.prepare("virtual-folder",expected,selected,physical){1_700_000_000_000L}
        assertEquals(expected,proof.originals)
        assertEquals(proof,SelectiveArchiveCheckpointStore(dir).read("virtual-folder"))
        assertEquals(proof,a.prepare("virtual-folder",expected,selected,physical){1_700_000_000_010L})
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                a.prepare("virtual-folder",expected.mapIndexed { i,r ->
                    if(i==0)r.copy(sha256="c".repeat(64)) else r
                },selected,physical)
            }
        }
        assertEquals(proof,a.read("virtual-folder"))
        assertNull(a.read("unrelated-folder"))
    }

    @Test fun invalidSelectionAndChangedPhysicalInventoryNeverRecordCheckpoint()=runBlocking {
        val dir=temp.newFolder("archive-invalid")
        val store=SelectiveArchiveCheckpointStore(dir)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.prepare("virtual",identities(),selected,selected) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.prepare("virtual",identities(),selected,physical.drop(2)) }
        }
        assertNull(store.read("virtual"))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun corruptionAndUnknownPhaseFailClosedWithoutReplacingBytes()=runBlocking {
        val dir=temp.newFolder("archive-corrupt")
        val store=SelectiveArchiveCheckpointStore(dir)
        store.prepare("virtual",identities(),selected,physical){1_700_000_000_000L}
        val file=dir.listFiles()!!.single { it.name.endsWith(".binding") }
        val raw=file.readBytes()
        file.writeBytes(raw.copyOf().apply { this[5]=(this[5].toInt() xor 4).toByte() })
        val corrupt=file.readBytes()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SelectiveArchiveCheckpointStore(dir).read("virtual") }
        }
        assertArrayEquals(corrupt,file.readBytes())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.prepare("virtual",identities(),selected,physical) }
        }
        assertArrayEquals(corrupt,file.readBytes())
    }
    @Test fun listingAllDurableCheckpointsWorksWithoutCurrentCatalogAssignments()=runBlocking {
        val dir=temp.newFolder("archive-enumeration")
        val store=SelectiveArchiveCheckpointStore(dir)
        assertTrue(store.listAll().isEmpty())
        val proof=store.prepare("deleted-virtual-album",identities(),selected,physical) {
            1_700_000_000_000L
        }
        assertEquals(listOf(proof),SelectiveArchiveCheckpointStore(dir).listAll())
        // A stale uncommitted temporary file is not proof that a transfer began.
        File(dir,".bindings-old.tmp").writeText("incomplete")
        assertEquals(listOf(proof),store.listAll())
    }

    @Test fun unknownArchiveProofFailsClosedInsteadOfReportingEmpty()=runBlocking {
        val dir=temp.newFolder("archive-unknown")
        val store=SelectiveArchiveCheckpointStore(dir)
        File(dir,"unexpected.binding").writeText("untrusted")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.listAll() }
        }
    }

    @Test fun checksumTamperingPreventsEnumeratingDurableArchive()=runBlocking {
        val dir=temp.newFolder("archive-bad-hash")
        val store=SelectiveArchiveCheckpointStore(dir)
        store.prepare("safety-folder",identities(),selected,physical) { 1_700_000_000_000L }
        val proof=dir.listFiles()!!.single { it.name.endsWith(".binding") }
        proof.writeBytes(proof.readBytes().apply { this[4]=(this[4].toInt() xor 0x40).toByte() })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.listAll() }
        }
    }

}
