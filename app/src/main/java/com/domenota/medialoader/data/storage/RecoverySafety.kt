package com.domenota.medialoader.data.storage

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock

internal object RecoverySafety {
    const val RESERVE = 128L * 1024 * 1024
    fun enoughSpace(bytes: Long, internal: Long, external: Long): Boolean =
        bytes in 1..(100L * 1024 * 1024) && internal > RESERVE + bytes * 4 && external > RESERVE + bytes * 4

    fun identity(owner: String?, app: String, path: String?, name: String?, expectedName: String,
        mime: String?, expectedMime: String): Boolean = owner == app && path == RecoverySandbox.PATH &&
        name == expectedName && mime == expectedMime
}

/** Process-wide and cross-process exclusion, shared across all sandbox instances. */
internal object RecoveryExclusive {
    private val process = ReentrantLock()
    fun <T> run(file: File, block: () -> T): T {
        check(!process.isHeldByCurrentThread && process.tryLock()) { "Другая проба ещё выполняется" }
        try {
            RandomAccessFile(file, "rw").use { f ->
                val locked = f.channel.tryLock() ?: error("Операция занята другим процессом")
                try { return block() } finally { locked.release() }
            }
        } finally { process.unlock() }
    }
}
