package com.cdb96.ncmconverter4a.converter

import com.cdb96.ncmconverter4a.jni.KGMDecrypt
import com.cdb96.ncmconverter4a.util.DirectBufferPool
import com.cdb96.ncmconverter4a.util.DirectBufferPool.safeWrite
import java.io.FileInputStream
import java.nio.channels.FileChannel

object KGMConverter {

    private const val HEADER_LENGTH = 1024

    fun write(inputChannel: FileChannel, outputChannel: FileChannel, ownKeyBytes: ByteArray) {
        KGMDecrypt.init(ownKeyBytes)

        inputChannel.position(1024)

        val bufferSlot = DirectBufferPool.acquireDirectBuffer()
            ?: throw IllegalStateException("无法获取直接缓冲区")
        bufferSlot.use { slot ->
            val buffer = slot.buffer
            var pos = 0
            var bytesRead: Int
            while (inputChannel.read(buffer).also { bytesRead = it } != -1) {
                pos = KGMDecrypt.decrypt(buffer, pos, bytesRead)
                safeWrite(outputChannel, buffer)
            }
        }
    }

    fun getOwnKeyBytes(inputStream: FileInputStream): ByteArray {
        val ownKeyBytes = ByteArray(17)

        // KGM magic header实际长度为16字节,上面为了简化检测步骤弄成两个字节，这里16-2补回来
        inputStream.skip((16 - 2 + 8 + 4).toLong())
        inputStream.read(ownKeyBytes)
        ownKeyBytes[16] = 0
        // 减去之前读取的字节
        inputStream.skip((HEADER_LENGTH - 17 - 8 - 4 - 16).toLong())

        return ownKeyBytes
    }

    fun detectFormat(inputStream: FileInputStream, ownKeyBytes: ByteArray): String {
        val formatIdentifier = ByteArray(1)
        val keyBytes = 0
        val maskV2PreDef0 = 0xB8.toByte()
        inputStream.read(formatIdentifier)

        var med8 = ownKeyBytes[0].toInt() xor formatIdentifier[0].toInt()
        med8 = med8 xor ((med8 and 0xf) shl 4)
        var msk8 = keyBytes xor maskV2PreDef0.toInt()
        msk8 = msk8 xor ((msk8 and 0xf) shl 4)
        formatIdentifier[0] = (med8 xor msk8).toByte()

        return when (formatIdentifier[0]) {
            0x66.toByte() -> "flac"
            0x49.toByte() -> "mp3"
            else -> ""
        }
    }
}
