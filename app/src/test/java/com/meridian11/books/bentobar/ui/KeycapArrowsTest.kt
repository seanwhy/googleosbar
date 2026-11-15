package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.util.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A keycap of arrows is drawn with the window's own symbols; a cap of words, or of anything else, with its words. */
class KeycapArrowsTest {
    @Test fun aCapOfArrowsIsItsSymbolsInOrder() {
        assertEquals(listOf(Sym.ARROW_BACK, Sym.ARROW_FORWARD), arrowSymbols("\u2190 \u2192"))
        assertEquals(listOf(Sym.ARROW_UPWARD, Sym.ARROW_DOWNWARD), arrowSymbols("\u2191 \u2193"))
        assertEquals(listOf(Sym.ARROW_FORWARD), arrowSymbols("\u2192"))
    }

    @Test fun aCapOfWordsKeepsItsWords() {
        assertNull(arrowSymbols("Alt"))
        assertNull(arrowSymbols("Del"))
        assertNull(arrowSymbols(""))
        assertNull(arrowSymbols(" "))
        // An arrow among words is the words' business.
        assertNull(arrowSymbols("\u2190 Back"))
        assertNull(arrowSymbols("\u2190\u2192"))
    }
}
