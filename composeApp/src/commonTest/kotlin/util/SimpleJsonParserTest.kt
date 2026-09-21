package com.cdb96.ncmconverter4a.util

import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleJsonParserTest {
    @Test
    fun decodesUnicodeEscapes() {
        val values = SimpleJsonParser.parse("{\"title\":\"\\u4E2D\\u6587 \\uD83C\\uDFB5\"}")

        assertEquals(listOf("title", "中文 🎵"), values)
    }
}
