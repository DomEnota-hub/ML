package com.domenota.medialoader.data.catalog.visibility

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SelectiveArchiveSourceInspectorTest {
    private val signatures=(1..6).map { NomediaMediaSignature("track-$it.mp3",100L+it) }
    private val physical=(1..806).map { NomediaMediaSignature("track-$it.mp3",100L+it) }
    private val selected=(1..6).map { SelectiveArchiveCatalogMember(
        "download-$it","content://media/external/audio/media/$it","b".repeat(64)
    ) }
    private fun state(uri:String):SelectiveArchiveMediaState {
        val id=uri.substringAfterLast('/').toLong()
        return SelectiveArchiveMediaState(uri,id,"external_primary","Music/Shared/",
            "track-$id.mp3",100+id,1_700_000_000_000L+id,"audio/mpeg",false,false)
    }
    private class Probe(
        val states:(String,Int)->SelectiveArchiveMediaState?,
        val bytes:(String)->Long?={uri->100L+uri.substringAfterLast('/').toLong()},
    ):SelectiveArchiveMediaProbe {
        val count=mutableMapOf<String,Int>()
        override suspend fun inspect(uri:String):SelectiveArchiveMediaState? {
            val n=count.getOrDefault(uri,0)+1;count[uri]=n
            return states(uri,n)
        }
        override suspend fun fingerprint(uri:String):SelectiveArchivePayloadDigest? =
            bytes(uri)?.let { SelectiveArchivePayloadDigest(it,"a".repeat(64)) }
    }

    @Test fun sixOf806CanBeReadAndVerifiedWithoutTouchingTheOther800()=runBlocking {
        val probe=Probe({uri,_->state(uri)})
        val result=SelectiveArchiveSourceInspector(probe).inspect(selected,signatures,physical)
        assertEquals(6,result.size)
        assertEquals((1..6).map { it.toLong() },result.map { it.mediaStoreId })
        assertTrue(probe.count.values.all { it==2 })
        assertEquals("Music/Shared/",result.first().relativePath)
    }

    @Test fun mediaStoreRowChangingDuringHashNeverProducesEvidence()=runBlocking {
        val first=selected.first().originalUri
        val probe=Probe({uri,n->
            if(uri==first&&n==2)state(uri).copy(mediaStoreId=999)
            else state(uri)
        })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SelectiveArchiveSourceInspector(probe).inspect(selected,signatures,physical) }
        }
    }

    @Test fun truncationRevokedAccessPendingAndTrashMustFailClosed()=runBlocking {
        val failures=listOf(
            Probe({uri,_->state(uri)},{_->0L}),
            Probe({uri,_->if(uri==selected[0].originalUri)null else state(uri)}),
            Probe({uri,_->state(uri).copy(pending=true)}),
            Probe({uri,_->state(uri).copy(trashed=true)}),
            Probe({uri,_->state(uri).copy(modifiedAtMillis=0L)}),
        )
        failures.forEach { probe ->
            assertThrows(IllegalStateException::class.java) {
                runBlocking { SelectiveArchiveSourceInspector(probe).inspect(selected,signatures,physical) }
            }
        }
    }

    @Test fun unchangedPhysicalFolderIsNotSelectiveArchiving()=runBlocking {
        val probe=Probe({uri,_->state(uri)})
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SelectiveArchiveSourceInspector(probe).inspect(selected,signatures,signatures) }
        }
        assertTrue(probe.count.isEmpty())
    }
}
