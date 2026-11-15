package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.ui.accessRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The accesses some items need: each has its words, and Setup has one row for each that a registered type needs. */
class AccessesTest {
    private class Needs(type: String, override val needs: List<Access>) : ItemType(type, 0, "sym", 0) {
        override val group = ItemGroup.TOOLS
        override fun state(item: ItemConfig) = ItemState()
    }

    @Test fun everyAccessHasItsWords() {
        for (access in Access.entries) {
            assertNotEquals("$access: no title", 0, Accesses.title(access))
            assertNotEquals("$access: no explanation", 0, Accesses.explain(access))
            assertNotEquals("$access: no button", 0, Accesses.allow(access))
        }
        assertEquals("no two say the same", Access.entries.size, Access.entries.map { Accesses.title(it) }.toSet().size)
    }

    @Test fun setupHasOneRowForEachAccessATypeNeedsInTheirOwnOrder() {
        val types = listOf(
            Needs("brightness", listOf(Access.WRITE_SETTINGS)),
            Needs("mirror", listOf(Access.CAMERA)),
            Needs("plain", emptyList()),
            Needs("both", listOf(Access.WRITE_SETTINGS, Access.CAMERA)),
        )
        assertEquals(listOf(Access.CAMERA, Access.WRITE_SETTINGS), accessRows(types))
    }

    @Test fun noTypeThatNeedsOneNoRow() {
        assertEquals(emptyList<Access>(), accessRows(emptyList()))
        assertEquals(emptyList<Access>(), accessRows(listOf(Needs("plain", emptyList()))))
    }
}
