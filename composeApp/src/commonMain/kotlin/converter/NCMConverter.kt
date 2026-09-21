package com.cdb96.ncmconverter4a.converter

import com.cdb96.ncmconverter4a.crypto.AESDecrypt
import com.cdb96.ncmconverter4a.io.BinaryInput
import com.cdb96.ncmconverter4a.io.BinaryOutput
import com.cdb96.ncmconverter4a.io.readAtMost
import com.cdb96.ncmconverter4a.io.readFully
import com.cdb96.ncmconverter4a.io.skipFully
import com.cdb96.ncmconverter4a.io.write
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

/**
 * NCM header parsing and streaming audio conversion shared by Android and
 * Desktop. Only bounded metadata and the current audio chunks are held in
 * memory; the encrypted payload is never materialized as one array.
 */
object NCMConverter {
    const val AUDIO_BUFFER_SIZE = 256 * 1024

    private const val NCM_HEADER_SIZE = 10
    private const val MAX_KEY_LENGTH = 4 * 1024
    private const val MAX_METADATA_LENGTH = 16 * 1024 * 1024
    private const val MAX_COVER_LENGTH = 64 * 1024 * 1024
    private const val MAX_ID3_LENGTH = 16 * 1024 * 1024
    private const val MAX_FLAC_METADATA_LENGTH = 16 * 1024 * 1024

    private val ncmMagic = "CTENFDAM".encodeToByteArray()
    private val coreKey = byteArrayOf(
        0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
        0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57
    )
    private val metaKey = byteArrayOf(
        0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
        0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
    )

    /** Reads the NCM header; raw conversion can validate and skip the unused cover. */
    fun readHeader(input: BinaryInput, includeCover: Boolean = true): NcmFileInfo {
        val magic = ByteArray(ncmMagic.size)
        input.readFully(magic)
        require(magic.contentEquals(ncmMagic)) { "invalid NCM magic" }

        // NCM stores two reserved/version bytes after CTENFDAM.
        input.readFully(ByteArray(NCM_HEADER_SIZE - ncmMagic.size))

        val rc4Key = readRc4Key(input)
        val metadata = readMetadata(input)

        val musicInfo = SimpleJsonParser.parse(metadata.decodeToString())
        val musicName = requiredField(musicInfo, "musicName")
        val musicAlbum = requiredField(musicInfo, "album")
        val musicArtists = combineArtistsString(requiredField(musicInfo, "artist"))
        val format = requiredField(musicInfo, "format").lowercase()
        require(format == "mp3" || format == "flac") {
            "unsupported NCM audio format: $format"
        }

        val coverData = readCoverData(input, includeCover)
        return NcmFileInfo(rc4Key, coverData, musicName, musicAlbum, musicArtists, format)
    }

    /**
     * Decrypts the payload from the current input position. The caller must
     * have called [readHeader] on the same input first.
     */
    fun writeAudio(
        input: BinaryInput,
        output: BinaryOutput,
        info: NcmFileInfo,
        rawWriteMode: Boolean,
        bufferSize: Int = AUDIO_BUFFER_SIZE
    ) {
        require(bufferSize > 0 && bufferSize % 256 == 0) {
            "NCM buffer size must be a positive multiple of 256"
        }

        // Each RC4 decrypt call starts again at the beginning of the 256-byte
        // keystream. Filling every non-final chunk to a multiple of 256 keeps the
        // stream position consistent across calls without buffering the whole file.
        RC4Decrypt.ksa(info.RC4key)
        val reader = DecryptedPayloadReader(input, bufferSize)

        val prefix = ByteArray(3)
        val prefixSize = reader.read(prefix, 0, prefix.size)
        if (prefixSize == 0) throw IllegalArgumentException("NCM payload is empty")
        if (rawWriteMode) {
            output.write(prefix, 0, prefixSize)
            reader.copyTo(output)
            return
        }

        when {
            prefix.contentEquals("ID3".toByteArray()) -> {
                val id3Header = ByteArray(10)
                prefix.copyInto(id3Header)
                reader.readFully(id3Header, prefixSize, id3Header.size - prefixSize)
                val id3Length = LengthUtils.getSyncSafeInteger(id3Header.copyOfRange(6, 10))
                require(id3Length in 0..MAX_ID3_LENGTH) {
                    "invalid ID3 length: $id3Length"
                }
                // The size field excludes the ten-byte footer an ID3v2.4 tag may
                // append (flag bit 4), so it is skipped separately.
                val hasFooter = id3Header[3].toInt() == 4 && (id3Header[5].toInt() and 0x10) != 0
                reader.skipFully(id3Length.toLong() + if (hasFooter) 10L else 0L)

                ID3TagBuilder().apply {
                    initDefaultTagHeader()
                    addTIT2(info.musicName)
                    addTPE1(info.musicArtists)
                    addTALB(info.musicAlbum)
                    addCover(info.coverData)
                }.writeTo(output)
                reader.copyTo(output)
            }

           prefix.contentEquals("fLa".toByteArray()) -> {
               reader.skipFully(1)
               writeFlacMetadata(reader, output, info)
                reader.copyTo(output)
            }

            else -> {
                // Keep the historical behavior for an unknown decrypted audio
                // header: decrypt the payload, but do not invent metadata.
                output.write(prefix, 0, prefixSize)
                reader.copyTo(output)
            }
        }
    }

    /** Kept for platform-independent AES regression tests and key extraction. */
    fun decrypt(key: ByteArray, encryptedBytes: ByteArray): ByteArray =
        AESDecrypt.decryptEcbPkcs5(key, encryptedBytes)

    private fun readRc4Key(input: BinaryInput): ByteArray {
        val keyLength = readLength(input, "key", minimum = 1, maximum = MAX_KEY_LENGTH)
        val encryptedKey = ByteArray(keyLength)
        input.readFully(encryptedKey)
        for (index in encryptedKey.indices) {
            encryptedKey[index] = (encryptedKey[index].toInt() xor 0x64).toByte()
        }

        val decrypted = decrypt(coreKey, encryptedKey)
        require(decrypted.size > 17) { "NCM RC4 key payload is truncated" }
        return decrypted.copyOfRange(17, decrypted.size).also {
            require(it.isNotEmpty()) { "NCM RC4 key is empty" }
        }
    }

    private fun readMetadata(input: BinaryInput): ByteArray {
        val metadataLength = readLength(input, "metadata", minimum = 22, maximum = MAX_METADATA_LENGTH)
        val raw = ByteArray(metadataLength)
        input.readFully(raw)
        for (index in raw.indices) {
            raw[index] = (raw[index].toInt() xor 0x63).toByte()
        }

        require(raw.size > 22) { "NCM metadata payload is empty" }
        val decoded = Base64.decode(raw, startIndex = 22)
        return decrypt(metaKey, decoded)
    }

    private fun readCoverData(input: BinaryInput, includeCover: Boolean): ByteArray {
        input.readFully(ByteArray(5))

        val coverLength = readLength(input, "cover", minimum = 0, maximum = MAX_COVER_LENGTH)
        val imageLength = readLength(input, "cover image", minimum = 0, maximum = coverLength)
        if (!includeCover) {
            input.skipFully(coverLength.toLong())
            return ByteArray(0)
        }
        val image = ByteArray(imageLength)
        input.readFully(image)
        input.skipFully((coverLength - imageLength).toLong())
        return image
    }

    private fun readLength(
        input: BinaryInput,
        field: String,
        minimum: Int,
        maximum: Int
    ): Int {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        val length = LengthUtils.readIntLEInline(bytes, 0)
        require(length in minimum..maximum) {
            "invalid $field length: $length (expected $minimum..$maximum)"
        }
        return length
    }

    private fun requiredField(values: List<String>, field: String): String {
        // The parser appends key/value pairs, so keys sit at even indices. A
        // value that happens to equal a key name must not be mistaken for it.
        for (index in values.indices step 2) {
            if (values[index] == field && index + 1 < values.size) return values[index + 1]
        }
        throw IllegalArgumentException("NCM metadata is missing field: $field")
    }

    /**
     * The NCM "artist" field is a JSON array of `[name, id]` pairs. Names are
     * read from their quoted strings, so commas and escapes inside a name
     * survive. A plain string value is used as-is.
     */
    internal fun combineArtistsString(artistsString: String): String {
        val value = artistsString.trim()
        if (!value.startsWith("[")) return value

        val names = ArrayList<String>()
        var depth = 0
        var firstInPair = false
        var index = 0
        while (index < value.length) {
            when (value[index]) {
                '[' -> { depth++; firstInPair = true }
                ']' -> depth--
                '"' -> {
                    val end = SimpleJsonParser.findStringEnd(value, index)
                    if (end < 0) break
                    if (depth == 1 || firstInPair) {
                        names += SimpleJsonParser.decodeJsonString(value.substring(index + 1, end))
                    }
                    firstInPair = false
                    index = end
                }
            }
            index++
        }
        return names.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
    }

    private fun writeFlacMetadata(
        reader: DecryptedPayloadReader,
        output: BinaryOutput,
        info: NcmFileInfo
    ) {
        output.write("fLaC".encodeToByteArray())
        val blockHeader = ByteArray(4)
        var totalLength = 4L // fLaC signature already consumed
        var vorbisRewritten = false
        // Without cover art no picture block is inserted, so the rewritten
        // Vorbis block itself may have to carry the last-block flag.
        val hasCover = info.coverData.isNotEmpty()

        while (true) {
            reader.readFully(blockHeader)
            val typeByte = blockHeader[0].toInt() and 0xFF
            val type = typeByte and 0x7F
            val isLast = (typeByte and 0x80) != 0
            val bodyLength = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                ((blockHeader[2].toInt() and 0xFF) shl 8) or
                (blockHeader[3].toInt() and 0xFF)
            totalLength += 4L + bodyLength
            require(totalLength <= MAX_FLAC_METADATA_LENGTH) {
                "FLAC metadata is too large: $totalLength"
            }

            if (type == 4 && !vorbisRewritten) {
                require(bodyLength >= 4) { "truncated FLAC Vorbis comment block" }
                val vendorLengthBytes = ByteArray(4)
                reader.readFully(vendorLengthBytes)
                val vendorLength = LengthUtils.readIntLEInline(vendorLengthBytes, 0)
                require(vendorLength >= 0 && vendorLength <= bodyLength - 4) {
                    "invalid FLAC Vorbis vendor length: $vendorLength"
                }

                FLACMetadataGenerator.writeVorbisCommentBlock(
                    output, info.musicName, info.musicArtists, info.musicAlbum, vendorLength,
                    isLast = isLast && !hasCover
                ) { vendorOutput -> reader.copyTo(vendorOutput, vendorLength.toLong()) }
                reader.skipFully((bodyLength - 4 - vendorLength).toLong())
                vorbisRewritten = true
                if (isLast && hasCover) {
                    FLACMetadataGenerator.writePictureBlock(output, info.coverData, isLast = true)
                }
            } else {
                if (isLast && vorbisRewritten && hasCover) {
                    FLACMetadataGenerator.writePictureBlock(output, info.coverData, isLast = false)
                }
                output.write(blockHeader)
                reader.copyTo(output, bodyLength.toLong())
            }
            if (isLast) return
        }
    }

    private class DecryptedPayloadReader(
        private val input: BinaryInput,
        bufferSize: Int
    ) {
        private val encryptedBuffer = ByteArray(bufferSize)
        private var position = 0
        private var limit = 0
        private var endOfInput = false

        private fun ensureAvailable(): Boolean {
            if (position < limit) return true
            if (endOfInput) return false
            val bytesRead = input.readAtMost(encryptedBuffer)
            if (bytesRead == 0) {
                endOfInput = true
                return false
            }
            RC4Decrypt.prgaDecrypt(encryptedBuffer, bytesRead)
            position = 0
            limit = bytesRead
            return true
        }

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
                "invalid payload read range"
            }
            if (length == 0) return 0

            var total = 0
            while (total < length) {
                if (!ensureAvailable()) break

                val copied = minOf(length - total, limit - position)
                encryptedBuffer.copyInto(buffer, offset + total, position, position + copied)
                position += copied
                total += copied
            }
            return total
        }

        fun readFully(buffer: ByteArray) {
            readFully(buffer, 0, buffer.size)
        }

        fun readFully(buffer: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
                "invalid payload read range"
            }
            var position = offset
            val end = offset + length
            while (position < end) {
                val bytesRead = read(buffer, position, end - position)
                if (bytesRead <= 0) {
                    throw IllegalArgumentException("unexpected end of decrypted NCM payload")
                }
                position += bytesRead
            }
        }

        fun skipFully(bytes: Long) {
            require(bytes >= 0) { "cannot skip a negative payload length" }
            var remaining = bytes
            while (remaining > 0) {
                if (!ensureAvailable()) {
                    throw IllegalArgumentException("unexpected end of decrypted NCM payload")
                }
                val skipped = minOf(remaining, (limit - position).toLong()).toInt()
                position += skipped
                remaining -= skipped
            }
        }

        fun copyTo(output: BinaryOutput, bytes: Long) {
            require(bytes >= 0) { "cannot copy a negative payload length" }
            var remaining = bytes
            while (remaining > 0) {
                if (!ensureAvailable()) {
                    throw IllegalArgumentException("unexpected end of decrypted NCM payload")
                }
                val copied = minOf(remaining, (limit - position).toLong()).toInt()
                output.write(encryptedBuffer, position, copied)
                position += copied
                remaining -= copied
            }
        }

        fun copyTo(output: BinaryOutput) {
            while (ensureAvailable()) {
                output.write(encryptedBuffer, position, limit - position)
                position = limit
            }
        }
    }
}
