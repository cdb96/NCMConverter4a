package com.cdb96.ncmconverter4a.converter

import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import com.cdb96.ncmconverter4a.tag.FLACMetadataGenerator
import com.cdb96.ncmconverter4a.tag.ID3TagBuilder
import com.cdb96.ncmconverter4a.util.DirectBufferPool
import com.cdb96.ncmconverter4a.util.DirectBufferPool.safeWrite
import com.cdb96.ncmconverter4a.util.LengthUtils
import com.cdb96.ncmconverter4a.util.SimpleJsonParser
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class NcmFileInfo(
    val RC4key: ByteArray,
    val coverData: ByteArray,
    val musicName: String,
    val musicAlbum: String,
    val musicArtists: String,
    val format: String
)

object NCMConverter {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/ECB/PKCS5Padding"

    fun decrypt(key: ByteArray, encryptedBytes: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, ALGORITHM)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        return cipher.doFinal(encryptedBytes)
    }

    private fun getRC4key(fileStream: InputStream): ByteArray {
        // 这里-2是因为之前检测KGM文件的时候已经读取两个字节了
        fileStream.skip((10 - 2).toLong())
        val coreKey = byteArrayOf(
            0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
            0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57
        )
        var bytes = ByteArray(4)
        fileStream.read(bytes, 0, 4)
        val keyLength = LengthUtils.getLittleEndianInteger(bytes)
        if (keyLength < 1024) { // 防止因导入错误的文件而崩溃
            bytes = ByteArray(keyLength)
        } else {
            throw Exception()
        }
        fileStream.read(bytes, 0, keyLength)
        for (i in 0 until keyLength) {
            bytes[i] = (bytes[i].toInt() xor 0x64).toByte()
        }
        bytes = decrypt(coreKey, bytes)
        val key = ByteArray(bytes.size - 17)
        System.arraycopy(bytes, 17, key, 0, key.size)
        return key
    }

    private fun getMetaData(fileStream: InputStream): ByteArray {
        val metaKey = byteArrayOf(
            0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
            0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
        )
        val rawMetaLength = ByteArray(4)
        fileStream.read(rawMetaLength, 0, 4)
        val metaLength = LengthUtils.getLittleEndianInteger(rawMetaLength)
        val rawMetaBytes = ByteArray(metaLength)
        fileStream.read(rawMetaBytes, 0, metaLength)
        for (i in 0 until metaLength) {
            rawMetaBytes[i] = (rawMetaBytes[i].toInt() xor 0x63).toByte()
        }
        var metaBytes = ByteArray(rawMetaBytes.size - 22)
        System.arraycopy(rawMetaBytes, 22, metaBytes, 0, metaBytes.size)
        metaBytes = Base64.getDecoder().decode(metaBytes)
        metaBytes = decrypt(metaKey, metaBytes)
        return metaBytes
    }

    private fun getCoverData(fileStream: InputStream): ByteArray {
        fileStream.skip(5L)
        val bytes = ByteArray(4)

        fileStream.read(bytes, 0, 4)
        val coverFrameLength = LengthUtils.getLittleEndianInteger(bytes)

        fileStream.read(bytes, 0, 4)
        val image1Length = LengthUtils.getLittleEndianInteger(bytes)

        val image1Data = ByteArray(image1Length)
        fileStream.read(image1Data, 0, image1Length)
        fileStream.skip((coverFrameLength - image1Data.size).toLong())

        return image1Data
    }

    fun expandByteArray(original: ByteArray, addtionalBytes: Int): ByteArray {
        if (original.size + addtionalBytes > 24 * 1024 * 1024) {
            throw OutOfMemoryError("扩展长度过长")
        }
        val newArray = ByteArray(original.size + addtionalBytes)
        System.arraycopy(original, 0, newArray, 0, original.size)
        return newArray
    }

    fun modifyHeader(
        fileStream: FileInputStream,
        fileOutputStream: FileOutputStream,
        info: NcmFileInfo,
        coverData: ByteArray,
        bufferSize: Int
    ) {
        val preFetchChunk = ByteArray(bufferSize)
        fileStream.read(preFetchChunk, 0, bufferSize)
        RC4Decrypt.prgaDecryptByteArray(preFetchChunk, preFetchChunk.size)

        when (preFetchChunk[0]) {
            0x49.toByte() -> modifyMp3Header(fileStream, fileOutputStream, info, coverData, bufferSize, preFetchChunk)
            0x66.toByte() -> modifyFlacHeader(fileStream, fileOutputStream, info, coverData, bufferSize, preFetchChunk)
        }
    }

    private fun modifyMp3Header(
        fileStream: FileInputStream,
        fileOutputStream: FileOutputStream,
        info: NcmFileInfo,
        coverData: ByteArray,
        bufferSize: Int,
        initialChunk: ByteArray
    ) {
        var preFetchChunk = initialChunk
        val id3Length = LengthUtils.getSyncSafeInteger(preFetchChunk.copyOfRange(6, 10))

        // 原始ID3标签超出预读范围时，扩容并继续读取解密
        if (id3Length > bufferSize) {
            val expandSize = (id3Length + bufferSize - 1) / bufferSize * bufferSize
            val extraSize = expandSize - bufferSize
            preFetchChunk = expandByteArray(preFetchChunk, extraSize)
            val temp = ByteArray(extraSize)
            val bytesRead = fileStream.read(temp, 0, extraSize)
            RC4Decrypt.prgaDecryptByteArray(temp, bytesRead)
            System.arraycopy(temp, 0, preFetchChunk, bufferSize, bytesRead)
        }

        val id3Header = ID3TagBuilder()
        id3Header.initDefaultTagHeader()
        id3Header.addTIT2(info.musicName)
        id3Header.addTPE1(info.musicArtists)
        id3Header.addTALB(info.musicAlbum)
        id3Header.addCover(coverData)

        // 写入新ID3头 + 原ID3标签之后的音频数据
        fileOutputStream.write(id3Header.outputHeader())
        fileOutputStream.write(preFetchChunk, id3Length, preFetchChunk.size - id3Length)
    }

    private fun modifyFlacHeader(
        fileStream: FileInputStream,
        fileOutputStream: FileOutputStream,
        info: NcmFileInfo,
        coverData: ByteArray,
        bufferSize: Int,
        initialChunk: ByteArray
    ) {
        // 持续扩容读取，直到包含最后一个元数据块
        var preFetchChunk = initialChunk
        while (!LengthUtils.hasLastBlock(preFetchChunk)) {
            val temp = ByteArray(bufferSize)
            fileStream.read(temp, 0, bufferSize)
            RC4Decrypt.prgaDecryptByteArray(temp, temp.size)
            preFetchChunk = expandByteArray(preFetchChunk, bufferSize)
            System.arraycopy(temp, 0, preFetchChunk, preFetchChunk.size - bufferSize, temp.size)
        }

        // 写入Vorbis Comment块之前的所有块
        val vorbisCommentBegin = LengthUtils.findVorbisComment(preFetchChunk)
        fileOutputStream.write(preFetchChunk, 0, vorbisCommentBegin)

        // 解析原Vorbis Comment块的长度与vendor信息
        val vorbisCommentSize = LengthUtils.getBigEndianInteger3bytes(
            preFetchChunk.copyOfRange(vorbisCommentBegin + 1, vorbisCommentBegin + 4)
        )
        val vendorLength = LengthUtils.getLittleEndianInteger(
            preFetchChunk.copyOfRange(vorbisCommentBegin + 4, vorbisCommentBegin + 8) // +4跳过块头
        )
        val vendorBytes = preFetchChunk.copyOfRange(
            vorbisCommentBegin + 8, vorbisCommentBegin + 8 + vendorLength // +8跳过块头和vendor长度字段
        )

        // 写入新生成的Vorbis Comment块
        val vorbisCommentBlock = FLACMetadataGenerator.vorbisCommentBlockGen(
            info.musicName, info.musicArtists, info.musicAlbum, vendorBytes
        )
        fileOutputStream.write(vorbisCommentBlock)

        // 写入原Vorbis Comment块与最后一个块之间的数据
        val vorbisCommentEnd = vorbisCommentSize + vorbisCommentBegin + 4 // +4因为size不包含块头
        val pictureBlockBegin = LengthUtils.findLastBlock(preFetchChunk)
        fileOutputStream.write(preFetchChunk, vorbisCommentEnd, pictureBlockBegin - vorbisCommentEnd)

        // 写入新Picture块 + 最后一个块之后的剩余数据
        fileOutputStream.write(FLACMetadataGenerator.pictureBlockGen(coverData))
        fileOutputStream.write(preFetchChunk, pictureBlockBegin, preFetchChunk.size - pictureBlockBegin)
    }

    fun outputMusic(outputChannel: FileChannel, inputChannel: FileChannel) {
        val bufferSlot = DirectBufferPool.acquireDirectBuffer()
            ?: throw IllegalStateException("无法获取直接缓冲区")
        bufferSlot.use { slot ->
            val buffer = slot.buffer
            var bytesRead: Int
            while (inputChannel.read(buffer).also { bytesRead = it } != -1) {
                RC4Decrypt.prgaDecryptByteBuffer(buffer, bytesRead)
                safeWrite(outputChannel, buffer)
            }
        }
    }

    private fun combineArtistsString(artistsString: String): String {
        val artistsStringArray = artistsString.replace(Regex("[\\\\\\[\\]\"]"), "").split(",")
        return artistsStringArray
            .filterIndexed { index, _ -> index % 2 == 0 }
            .joinToString("/") { it.trim() }
    }

    fun convert(fileStream: InputStream): NcmFileInfo {
        val rc4Key = getRC4key(fileStream)
        val metaBytes = getMetaData(fileStream)
        val coverData = getCoverData(fileStream)

        val metaData = String(metaBytes, Charsets.UTF_8)
        val musicInfo = SimpleJsonParser.parse(metaData)

        val musicName = musicInfo[musicInfo.indexOf("musicName") + 1]
        val musicAlbum = musicInfo[musicInfo.indexOf("album") + 1]
        var musicArtists = musicInfo[musicInfo.indexOf("artist") + 1]
        val format = musicInfo[musicInfo.indexOf("format") + 1]
        musicArtists = combineArtistsString(musicArtists)

        return NcmFileInfo(rc4Key, coverData, musicName, musicAlbum, musicArtists, format)
    }
}
