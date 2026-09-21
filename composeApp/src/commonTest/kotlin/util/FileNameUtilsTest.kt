package com.cdb96.ncmconverter4a.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileNameUtilsTest {
    @Test
    fun sanitizesWindowsCharactersAndTrailingWhitespace() {
        assertEquals("artist__title_", FileNameUtils.sanitizeFileName("artist:/title? "))
        assertEquals("Unknown", FileNameUtils.sanitizeFileName("...   "))
    }

    @Test
    fun protectsReservedWindowsNamesAndLimitsLength() {
        assertEquals("_CON.txt", FileNameUtils.sanitizeFileName("CON.txt"))
        assertEquals(180, FileNameUtils.sanitizeFileName("x".repeat(400)).length)
    }

    @Test
    fun limitsUtf8BytesInsteadOfCharacters() {
        val result = FileNameUtils.sanitizeFileName("中".repeat(180))

        assertTrue(result.encodeToByteArray().size <= 180)
        assertEquals("中".repeat(60), result)
    }
}
