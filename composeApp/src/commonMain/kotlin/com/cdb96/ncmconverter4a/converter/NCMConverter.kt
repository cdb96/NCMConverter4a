package com.cdb96.ncmconverter4a.converter

import com.cdb96.ncmconverter4a.crypto.AESDecrypt
import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import com.cdb96.ncmconverter4a.tag.FLACMetadataGenerator
import com.cdb96.ncmconverter4a.tag.ID3TagBuilder
import com.cdb96.ncmconverter4a.util.LengthUtils
import com.cdb96.ncmconverter4a.util.SimpleJsonParser
import kotlin.io.encoding.Base64

data class NcmFileInfo(
    val RC4key: ByteArray,
    val coverData: ByteArray,
    val musicName: String,
    val musicAlbum: String,
    val musicArtists: String,
    val format: String
)

data class HeaderResult(
    val headerBytes: ByteArray,
    val remainingData: ByteArray
)

object NCMConverter {
    private val coreKey = byteArrayOf(
        0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
        0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57
    )
    private val metaKey = byteArrayOf(
        0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
        0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
    )

    fun decrypt(key: ByteArray, encryptedBytes: ByteArray): ByteArray =
        AESDecrypt.decryptEcbPkcs5(key, encryptedBytes)

    fun convert(fileData: ByteArray): Pair<NcmFileInfo, Int> {
        var pos = 8  // fileData[0] 是文件字节 2 (前2字节已消费), 跳到文件字节 10 -> 下标 8
        val (rc4Key, rc4Consumed) = getRC4key(fileData, pos); pos += rc4Consumed
        val (metaBytes, metaConsumed) = getMetaData(fileData, pos); pos += metaConsumed
        val (coverData, coverConsumed) = getCoverData(fileData, pos); pos += coverConsumed

        val metaData = String(metaBytes, Charsets.UTF_8)
        val musicInfo = SimpleJsonParser.parse(metaData)
        val musicName = musicInfo[musicInfo.indexOf("musicName") + 1]
        val musicAlbum = musicInfo[musicInfo.indexOf("album") + 1]
        var musicArtists = musicInfo[musicInfo.indexOf("artist") + 1]
        val format = musicInfo[musicInfo.indexOf("format") + 1]
        musicArtists = combineArtistsString(musicArtists)
        return Pair(NcmFileInfo(rc4Key, coverData, musicName, musicAlbum, musicArtists, format), pos)
    }

    private fun getRC4key(data: ByteArray, offset: Int): Pair<ByteArray, Int> {
        var pos = offset
        val keyLen = LengthUtils.getLittleEndianInteger(data.copyOfRange(pos, pos + 4)); pos += 4
        require(keyLen in 1..<1024) { "invalid key length" }
        val bytes = ByteArray(keyLen)
        for (i in 0 until keyLen) bytes[i] = (data[pos + i].toInt() xor 0x64).toByte()
        pos += keyLen
        val dec = decrypt(coreKey, bytes)
        val key = ByteArray(dec.size - 17)
        dec.copyInto(key, 0, 17, dec.size)
        return Pair(key, pos - offset)
    }

    private fun getMetaData(data: ByteArray, offset: Int): Pair<ByteArray, Int> {
        var pos = offset
        val metaLen = LengthUtils.getLittleEndianInteger(data.copyOfRange(pos, pos + 4)); pos += 4
        val raw = ByteArray(metaLen)
        for (i in 0 until metaLen) raw[i] = (data[pos + i].toInt() xor 0x63).toByte()
        pos += metaLen
        val metaBytes = raw.copyOfRange(22, raw.size)
        val decoded = Base64.decode(metaBytes.decodeToString())
        return Pair(decrypt(metaKey, decoded), pos - offset)
    }

    private fun getCoverData(data: ByteArray, offset: Int): Pair<ByteArray, Int> {
        var pos = offset + 5  // skip 5
        val coverLen = LengthUtils.getLittleEndianInteger(data.copyOfRange(pos, pos + 4)); pos += 4
        val imgLen = LengthUtils.getLittleEndianInteger(data.copyOfRange(pos, pos + 4)); pos += 4
        val imgData = data.copyOfRange(pos, pos + imgLen); pos += coverLen
        return Pair(imgData, pos - offset)
    }

    private fun expandByteArray(original: ByteArray, additionalBytes: Int): ByteArray {
        if (original.size + additionalBytes > 24 * 1024 * 1024) {
            throw OutOfMemoryError("扩展长度过长")
        }
        val newArray = ByteArray(original.size + additionalBytes)
        original.copyInto(newArray)
        return newArray
    }

    // 辅助：拼接多个 ByteArray（模拟 v3.4 的顺序流写入）
    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        return ByteArray(total).also { result ->
            var w = 0
            for (p in parts) { p.copyInto(result, w); w += p.size }
        }
    }

    fun modifyHeader(
        encryptedData: ByteArray, info: NcmFileInfo, coverData: ByteArray, bufferSize: Int
    ): HeaderResult {
        // 预取并解密第一个块
        var preFetchChunk = encryptedData.copyOfRange(0, minOf(bufferSize, encryptedData.size))
        RC4Decrypt.prgaDecrypt(preFetchChunk, preFetchChunk.size)

        val musicName = info.musicName
        val musicAlbum = info.musicAlbum
        val musicArtist = info.musicArtists

        if (preFetchChunk[0] == 0x49.toByte()) {
            // ID3Length 使用同步安全整数
            val ID3Length = LengthUtils.getSyncSafeInteger(preFetchChunk.copyOfRange(6, 10))

            if (ID3Length > bufferSize) {
                val expandSizeFactor = (ID3Length + bufferSize - 1) / bufferSize
                val expandSize = expandSizeFactor * bufferSize
                preFetchChunk = expandByteArray(preFetchChunk, expandSize - bufferSize)
                val temp = encryptedData.copyOfRange(bufferSize, minOf(expandSize, encryptedData.size))
                RC4Decrypt.prgaDecrypt(temp, temp.size)
                temp.copyInto(preFetchChunk, bufferSize)
            }

            val ID3Header = ID3TagBuilder()
            ID3Header.initDefaultTagHeader()
            ID3Header.addTIT2(musicName)
            ID3Header.addTPE1(musicArtist)
            ID3Header.addTALB(musicAlbum)
            ID3Header.addCover(coverData)

            val mp3Header = ID3Header.outputHeader()
            // header + 已解密的音频数据 (preFetchChunk 中 ID3Length 之后的部分)
            val combined = ByteArray(mp3Header.size + (preFetchChunk.size - ID3Length))
            mp3Header.copyInto(combined)
            preFetchChunk.copyInto(combined, mp3Header.size, ID3Length, preFetchChunk.size)

            // preFetchChunk 之后剩余的数据, 需要由调用方继续 RC4 解密
            val remaining = if (preFetchChunk.size < encryptedData.size)
                encryptedData.copyOfRange(preFetchChunk.size, encryptedData.size) else ByteArray(0)
            return HeaderResult(combined, remaining)

        } else if (preFetchChunk[0] == 0x66.toByte()) {
            // FLAC: 一直读到找到最后一个 metadata block
            while (!LengthUtils.hasLastBlock(preFetchChunk) && preFetchChunk.size < encryptedData.size) {
                val start = preFetchChunk.size
                val temp = encryptedData.copyOfRange(start, minOf(start + bufferSize, encryptedData.size))
                RC4Decrypt.prgaDecrypt(temp, temp.size)
                val oldLen = preFetchChunk.size
                preFetchChunk = expandByteArray(preFetchChunk, temp.size)
                temp.copyInto(preFetchChunk, oldLen)
            }

            val vorbisCommentBegin = LengthUtils.findVorbisComment(preFetchChunk)

            // 读出原始 vendor 信息
            val vendorLength = LengthUtils.getLittleEndianInteger(
                preFetchChunk.copyOfRange(vorbisCommentBegin + 4, vorbisCommentBegin + 8))
            val vendorBytes = preFetchChunk.copyOfRange(
                vorbisCommentBegin + 8, vorbisCommentBegin + 8 + vendorLength)

            val vorbisCommentSize = LengthUtils.getBigEndianInteger3bytes(
                preFetchChunk.copyOfRange(vorbisCommentBegin + 1, vorbisCommentBegin + 4))
            val vorbisCommentBlock = FLACMetadataGenerator.vorbisCommentBlockGen(
                musicName, musicArtist, musicAlbum, vendorBytes)

            val vorbisCommentEnd = vorbisCommentSize + vorbisCommentBegin + 4  // +4 跳过块头
            val pictureBlockBegin = LengthUtils.findLastBlock(preFetchChunk)
            val pictureBlock = FLACMetadataGenerator.pictureBlockGen(coverData)

            // 按 v3.4 顺序流写入: 5 次 write
            val combined = concat(
                preFetchChunk.copyOfRange(0, vorbisCommentBegin),
                vorbisCommentBlock,
                preFetchChunk.copyOfRange(vorbisCommentEnd, pictureBlockBegin),
                pictureBlock,
                preFetchChunk.copyOfRange(pictureBlockBegin, preFetchChunk.size),
            )

            val remaining = if (preFetchChunk.size < encryptedData.size)
                encryptedData.copyOfRange(preFetchChunk.size, encryptedData.size) else ByteArray(0)
            return HeaderResult(combined, remaining)

        } else {
            return HeaderResult(ByteArray(0), encryptedData)
        }
    }

    private fun combineArtistsString(artistsString: String): String {
        val arr = artistsString.replace(Regex("[\\\\\\[\\]\"]"), "").split(",")
        return arr.filterIndexed { i, _ -> i % 2 == 0 }.joinToString("/") { it.trim() }
    }
}
