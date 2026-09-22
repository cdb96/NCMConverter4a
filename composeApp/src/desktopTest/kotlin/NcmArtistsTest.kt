package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.NCMConverter
import kotlin.test.Test
import kotlin.test.assertEquals

class NcmArtistsTest {
    @Test
    fun joinsArtistNames() {
        assertEquals(
            "歌手甲/周杰伦",
            NCMConverter.combineArtistsString("""[["歌手甲",1],["周杰伦",6452]]""")
        )
        assertEquals("Solo", NCMConverter.combineArtistsString(" Solo "))
        assertEquals("", NCMConverter.combineArtistsString("[]"))
    }
}
