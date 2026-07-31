package com.cdb96.ncmconverter4a.converter

import com.cdb96.ncmconverter4a.jni.KGMDecrypt

object KGMConverter {

    /**
     * 从 KGM 文件头读取 17 字节密钥。前 2 字节已被调用方消费用于格式检测。
     * @param header 文件头数据（至少 1024 字节）
     */
    fun getOwnKeyBytes(header: ByteArray): ByteArray {
        val ownKeyBytes = ByteArray(17)
        // header[0..1] 已消费；跳到 offset 30 (16-2+8+4)
        val keyOffset = 16 - 2 + 8 + 4
        header.copyInto(ownKeyBytes, 0, keyOffset, keyOffset + 17)
        ownKeyBytes[16] = 0
        return ownKeyBytes
    }

    /** 从已解密的第一字节检测音频格式 */
    fun detectFormat(firstByte: Byte, ownKeyBytes: ByteArray): String {
        var med8 = ownKeyBytes[0].toInt() xor firstByte.toInt()
        med8 = med8 xor ((med8 and 0xf) shl 4)
        var msk8 = 0 xor 0xB8.toInt()
        msk8 = msk8 xor ((msk8 and 0xf) shl 4)
        return when ((med8 xor msk8).toByte()) {
            0x66.toByte() -> "flac"
            0x49.toByte() -> "mp3"
            else -> ""
        }
    }

    /**
     * 解密 KGM 数据流（对应 v3.4 的 write 方法）。
     * 平台只需负责 I/O，解密循环逻辑集中在此。
     *
     * @param ownKeyBytes 文件密钥
     * @param firstChunk  已读取的第一个数据块（就地解密）
     * @param firstSize   第一块有效字节数
     * @param bufferSize  后续读取缓冲区大小
     * @param read  读取下一块: (buffer) -> bytesRead, 返回 -1 表示 EOF
     * @param write 写出已解密数据: (buffer, bytesToWrite) -> Unit
     */
    fun decrypt(
        ownKeyBytes: ByteArray,
        firstChunk: ByteArray,
        firstSize: Int,
        bufferSize: Int,
        read: (ByteArray) -> Int,
        write: (ByteArray, Int) -> Unit
    ) {
        KGMDecrypt.init(ownKeyBytes)

        var fileOffset = 0
        fileOffset = KGMDecrypt.decrypt(firstChunk, fileOffset, firstSize)
        write(firstChunk, firstSize)

        val buf = ByteArray(bufferSize)
        var bytesRead: Int
        while (read(buf).also { bytesRead = it } != -1) {
            fileOffset = KGMDecrypt.decrypt(buf, fileOffset, bytesRead)
            write(buf, bytesRead)
        }
    }
}
