package com.cdb96.ncmconverter4a.converter.kgg

import kotlinx.coroutines.CancellationException

internal fun interface KggKeyDatabase {
    fun getBytes(audioHash: String): ByteArray?
}

/** One database snapshot per batch, initialized only when a KGG needs a key. */
internal class KggBatchKeys(loadDatabase: () -> KggKeyDatabase) {
    // The synchronized lazy also shares a failed load, avoiding repeated reads
    // or Root prompts from parallel files. Cancellation remains retryable.
    private val database by lazy {
        try {
            Result.success(loadDatabase())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun getKey(audioHash: String): ByteArray {
        val ekey = database.getOrThrow().getBytes(audioHash)
            ?: throw IllegalStateException("ekey解析失败: hash=$audioHash")
        return deriveKey(ekey)
    }
}
