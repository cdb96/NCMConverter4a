package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.jni.KGMDecrypt
import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the Desktop JNI entries ([RC4Decrypt], [KGMDecrypt]) against known-good
 * vectors. The library is bundled by the `nativeBuild` Gradle task; when it is
 * missing (for example in a JDK-only environment) the tests report that the
 * native path is unavailable instead of failing obscurely.
 */
class NativeDecryptTest {
    @Test
    fun rc4MatchesGoldenVector() {
        requireNative()
        val key = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val actual = ByteArray(32) { it.toByte() }
        val expected = byteArrayOf(
            0x6D, 0x84.toByte(), 0xE4.toByte(), 0x70, 0x92.toByte(), 0x1D,
            0xEE.toByte(), 0x82.toByte(), 0xA1.toByte(), 0x5E, 0x4D, 0x6C,
            0xB4.toByte(), 0xD0.toByte(), 0x0E, 0x5A, 0x0B, 0x00,
            0xC2.toByte(), 0xC0.toByte(), 0xF1.toByte(), 0xFE.toByte(), 0x2D,
            0xE9.toByte(), 0xB4.toByte(), 0x98.toByte(), 0xA1.toByte(),
            0x8F.toByte(), 0x87.toByte(), 0x42, 0x9F.toByte(), 0x82.toByte()
        )

        RC4Decrypt.ksa(key)
        RC4Decrypt.prgaDecrypt(actual, actual.size)

        assertContentEquals(expected, actual)
    }

    @Test
    fun kgmKeepsChunkedResultsAligned() {
        requireNative()
        val key = ByteArray(17) { (it * 41 + 1).toByte() }
        val input = ByteArray(69632 * 2 + 4096) { (it * 23 + 9).toByte() }

        KGMDecrypt.init(key)
        val whole = input.copyOf()
        val wholeNext = KGMDecrypt.decrypt(whole, 0, whole.size)

        KGMDecrypt.init(key)
        val chunked = ByteArray(input.size)
        var offset = 0
        while (offset < input.size) {
            val size = minOf(4096, input.size - offset)
            val chunk = input.copyOfRange(offset, offset + size)
            offset = KGMDecrypt.decrypt(chunk, offset, size)
            chunk.copyInto(chunked, offset - size)
        }

        assertEquals(input.size, offset)
        assertEquals(input.size, wholeNext)
        assertContentEquals(whole, chunked)
    }

    private fun requireNative() {
        // Touching the object triggers NativeLibrary.load(); a missing library
        // surfaces as ExceptionInInitializerError, so catch Throwable.
        val available = try {
            RC4Decrypt.ksa(byteArrayOf(1))
            true
        } catch (error: Throwable) {
            println("[info] native core 不可用: ${error.message}")
            false
        }
        assertTrue(
            available,
            "native core 不可用: 请先运行 ./gradlew :composeApp:nativeBuild"
        )
    }
}
