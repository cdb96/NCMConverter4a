package com.cdb96.ncmconverter4a.util

import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleJsonParserTest {
    @Test
    fun keepsDelimitersInsideStringsAndAllowsWhitespace() {
        assertEquals(
            listOf("musicName", """A, \"B\" [live]""", "artist", """[["A]B",1]]""", "bitrate", "320000"),
            SimpleJsonParser.parse(
                """{ "musicName" : "A, \"B\" [live]", "artist" : [["A]B",1]], "bitrate" : 320000 }"""
            )
        )
    }

    @Test
    fun skipsIncompletePairs() {
        for (suffix in listOf("\"album", "\"album\"", "\"album\":", "\"album\":\"unfinished", "\"album\":[1")) {
            assertEquals(
                listOf("musicName", "Song"),
                SimpleJsonParser.parse("""{"musicName":"Song",$suffix""")
            )
        }
    }

    @Test
    fun parsesNcmMetadata() {
        val values = SimpleJsonParser.parse(
            """music:{"musicName":"中文 🎵","album":"专辑","artist":[["歌手甲",1],["歌手乙",2]],"bitrate":320000,"format":"mp3"}"""
        )

        assertEquals(
            listOf(
                "musicName", "中文 🎵", "album", "专辑",
                "artist", """[["歌手甲",1],["歌手乙",2]]""",
                "bitrate", "320000", "format", "mp3"
            ),
            values
        )
    }
}
