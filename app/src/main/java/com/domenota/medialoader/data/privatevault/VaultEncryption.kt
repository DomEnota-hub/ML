package com.domenota.medialoader.data.privatevault

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keys never enter app preferences, source archives, or the user-accessible file system.
 * Losing the AndroidKeyStore key makes the vault unrecoverable: stage 4 must provide safe export.
 */
internal object VaultKeyManager {
    private const val ALIAS = "com.domenota.medialoader.vault.aes256.v1"
    @Synchronized fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}

internal data class VaultCopyEvidence(val bytes: Long, val sha256: String)

/** Bounded AES-256-GCM streaming format: ASCII magic + 12-byte IV + ciphertext/tag.
 * A complete authenticated second read and SHA check is mandatory before publish.
 */
internal object VaultCipher {
    private val MAGIC = "MLVLT001".toByteArray(Charsets.US_ASCII)
    private const val BUFFER = 64 * 1024
    private fun hex(bytes:ByteArray)=bytes.joinToString("") { "%02x".format(it) }

    fun encrypt(input:InputStream,output:OutputStream,key:SecretKey):VaultCopyEvidence {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key)
        check(cipher.iv.size==12) { "Unsupported AES-GCM nonce" }
        output.write(MAGIC);output.write(cipher.iv)
        val hash=MessageDigest.getInstance("SHA-256")
        val buffer=ByteArray(BUFFER)
        var count=0L
        while(true) {
            val size=input.read(buffer)
            if(size<0)break
            if(size==0)continue
            hash.update(buffer,0,size)
            cipher.update(buffer,0,size)?.let(output::write)
            count+=size
        }
        output.write(cipher.doFinal())
        output.flush()
        return VaultCopyEvidence(count,hex(hash.digest()))
    }

    fun decrypt(input:InputStream,output:OutputStream,key:SecretKey):VaultCopyEvidence {
        val source=DataInputStream(input)
        val magic=ByteArray(MAGIC.size);source.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Unsupported vault content format" }
        val iv=ByteArray(12);source.readFully(iv)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv))
        val hash=MessageDigest.getInstance("SHA-256")
        var count=0L
        CipherInputStream(source,cipher).use { decrypted ->
            val buffer=ByteArray(BUFFER)
            while(true) {
                val size=decrypted.read(buffer)
                if(size<0)break
                if(size==0)continue
                hash.update(buffer,0,size);output.write(buffer,0,size);count+=size
            }
        }
        output.flush()
        return VaultCopyEvidence(count,hex(hash.digest()))
    }

    fun authenticatedDigest(input:InputStream,key:SecretKey):VaultCopyEvidence =
        decrypt(input,object:OutputStream(){
            override fun write(value:Int) {}
            override fun write(bytes:ByteArray,offset:Int,length:Int) {}
        },key)
}

/** Shared encrypted storage for future generic media vault and existing private content
 * only after a separately verified migration. Old filesDir/private-media is untouched.
 */
internal class VaultEncryptedFiles(
    context:Context,
    private val keyProvider:()->SecretKey = VaultKeyManager::key,
) {
    private val app=context.applicationContext
    private val root=File(app.noBackupFilesDir,"vault-media-v1").canonicalFile
    private val publicationLock=Any()
    private fun directory():File {
        if(!root.isDirectory && !root.mkdirs()) error("Cannot create protected directory")
        return root
    }
    private fun target(id:String):File {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "Invalid protected file ID" }
        val file=File(directory(),"$id.vault")
        require(file.canonicalFile.parentFile==root && file.canonicalFile==file.absoluteFile)
        return file
    }
    fun exists(id:String)=target(id).isFile

    /** Source must be an explicit content URI with current Android read permission.
     * Never deletes or mutates the source. A process crash leaves it untouched.
     */
    suspend fun verifiedCopy(id:String,source:Uri):VaultCopyEvidence=withContext(Dispatchers.IO) {
        require(source.scheme=="content") { "Only authorized content URIs may be imported" }
        val destination=target(id)
        val temp=File.createTempFile(".import-",".tmp",directory())
        try {
            val key=keyProvider()
            val claim=app.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val evidence=VaultCipher.encrypt(input,output,key)
                    output.fd.sync()
                    evidence
                }
            } ?: error("Source is unavailable")
            val readback=FileInputStream(temp).use { VaultCipher.authenticatedDigest(it,key) }
            check(claim==readback) { "Protected copy verification failed" }
            synchronized(publicationLock) {
                check(!destination.exists()) { "Protected record already exists" }
                check(temp.renameTo(destination)) { "Cannot publish verified protected copy" }
            }
            claim
        } finally { temp.delete() }
    }

    /** Readback is authenticated and bounded; no public MediaStore copy is produced. */
    suspend fun verify(id:String,expected:VaultCopyEvidence):Boolean=withContext(Dispatchers.IO) {
        runCatching { FileInputStream(target(id)).use {
            VaultCipher.authenticatedDigest(it,keyProvider()) == expected
        } }.getOrDefault(false)
    }
}
