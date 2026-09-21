package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.NCMConverter
import kotlin.test.Test
import kotlin.test.assertEquals

class NcmArtistsTest {
    @Test
    fun joinsArtistNamesWithoutSplittingOnCommasInsideNames() {
        assertEquals(
            "Tyler, The Creator/周杰伦",
            NCMConverter.combineArtistsString("[[\"Tyler, The Creator\",1],[\"周杰伦\",6452]]")
        )
    }

    @Test
    fun decodesEscapesAndAcceptsFlatOrPlainValues() {
        assertEquals("A\"B", NCMConverter.combineArtistsString("[[\"A\\\"B\",1]]"))
        assertEquals("周", NCMConverter.combineArtistsString("[[\"\\u5468\",1]]"))
        assertEquals("A/B", NCMConverter.combineArtistsString("[\"A\",\"B\"]"))
        assertEquals("Solo", NCMConverter.combineArtistsString(" Solo "))
        assertEquals("", NCMConverter.combineArtistsString("[]"))
    }
}
