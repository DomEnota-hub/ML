package com.domenota.medialoader.data.privatevault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class VaultImportCoreTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test fun encryptedCopyAuthenticatesEveryByteAndRejectsTampering() {
        val bytes=ByteArray(163841) { (it * 7).toByte() }
        val output=ByteArrayOutputStream()
        val expected=VaultCipher.encrypt(ByteArrayInputStream(bytes),output,key)
        assertEquals(bytes.size.toLong(),expected.bytes)
        val decoded=ByteArrayOutputStream()
        assertEquals(expected,VaultCipher.decrypt(ByteArrayInputStream(output.toByteArray()),decoded,key))
        assertArrayEquals(bytes,decoded.toByteArray())
        val damaged=output.toByteArray().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val verified=runCatching { VaultCipher.authenticatedDigest(ByteArrayInputStream(damaged),key) }
        assertTrue("Modified ciphertext/tag must fail authentication", verified.isFailure)
    }

    @Test fun encryptedMetadataAndTransitionGuardsDoNotClaimFalseHiding() {
        val ctx=ApplicationProvider.getApplicationContext<Context>()
        val index=VaultImportIndex(ctx) { key }
        val id=UUID.randomUUID().toString().replace("-","")
        val original="content://media/external/images/media/123"
        val entry=VaultImportRecord(id,VaultOrigin.EXTERNAL_MEDIA,original,
            "private-portrait.jpg",id,34,"a".repeat(64),
            VaultImportPhase.COPY_VERIFIED_PUBLIC_PRESENT,1700000000000L)
        index.persist(entry)
        assertEquals(entry,index.read(id))
        val stored=File(ctx.noBackupFilesDir,"vault-index-v1/$id.record").readBytes()
        assertFalse(String(stored,Charsets.ISO_8859_1).contains(entry.displayName))
        assertFalse(String(stored,Charsets.ISO_8859_1).contains(original))
        assertFalse(VaultImportPolicy.canRequestRemoval(entry.phase,false,true,true))
        assertFalse(VaultImportPolicy.canRequestRemoval(entry.phase,true,false,true))
        assertFalse(VaultImportPolicy.canRequestRemoval(entry.phase,true,true,false))
        assertTrue(VaultImportPolicy.canRequestRemoval(entry.phase,true,true,true))
        assertFalse(VaultImportPolicy.advance(entry.phase,VaultImportPhase.FULLY_HIDDEN))
        assertTrue(VaultImportPolicy.advance(entry.phase,VaultImportPhase.AWAITING_PUBLIC_REMOVAL))
        index.persist(entry.copy(phase=VaultImportPhase.AWAITING_PUBLIC_REMOVAL))
        assertFalse(VaultImportPolicy.advance(VaultImportPhase.AWAITING_PUBLIC_REMOVAL,VaultImportPhase.FULLY_HIDDEN))
        assertTrue(VaultImportPolicy.advance(VaultImportPhase.AWAITING_PUBLIC_REMOVAL,VaultImportPhase.FULLY_HIDDEN,true))
        assertTrue(runCatching { index.persist(entry.copy(phase=VaultImportPhase.FULLY_HIDDEN)) }.isFailure)
        assertEquals(VaultImportPhase.AWAITING_PUBLIC_REMOVAL,index.read(id).phase)
    }

    @Test fun onlyMetadataCanBeRecordedWithoutRemovingPublicSource() {
        val phase=VaultImportPhase.COPY_VERIFIED_PUBLIC_PRESENT
        assertFalse(VaultImportPolicy.advance(phase,VaultImportPhase.PUBLIC_REMOVAL_FAILED))
        assertFalse(VaultImportPolicy.advance(VaultImportPhase.FULLY_HIDDEN,phase))
    }
}
