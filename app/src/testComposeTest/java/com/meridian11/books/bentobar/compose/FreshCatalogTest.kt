package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Part
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.fresh
import com.meridian11.books.bentobar.data.partOf
import com.meridian11.books.bentobar.data.without
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.Items
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The library's + for every type in the catalog: the item as it comes, and where it comes. Here and not with the pure
 * tests because one type's defaults are a text of the app's. A new type is covered by being in [Items.all].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class FreshCatalogTest {
    @Before fun app() { Env.init(ApplicationProvider.getApplicationContext<Application>()) }
    @After fun forget() { Store.update { BarConfig() } }

    @Test fun aFreshItemOfEveryTypeIsInTheBarOrWaitsOnARuleItHas() {
        assertTrue(Items.all.size > 20)
        for (type in Items.all) {
            val item = type.fresh("x", Position.RIGHT)
            // A type that waits with its rule on has a rule to wait on, and the default mode lists it under the line.
            if (type.addsWhenActive) {
                assertTrue(type.type, type.canBeActive)
                assertEquals(type.type, Part.LOWER, BarConfig(items = listOf(item)).partOf(item))
            } else assertEquals(type.type, Section.SHOWN, item.section)
        }
    }

    @Test fun thePlusAddsAFreshItemAndRemovingItAgainLeavesNothing() {
        Store.update { BarConfig(items = listOf(ItemConfig("left", "clock", area = Position.LEFT))) }
        for (type in Items.all) {
            val id = Store.addFresh(type)
            val added = Store.config.value.items.single { it.id == id }
            assertEquals(type.type, type.fresh(id, Position.LEFT), added)
            Store.update { it.without(id) }
            assertEquals(type.type, listOf("left"), Store.config.value.items.map { it.id })
        }
        val id = Store.addFresh(Items.of("cpu")!!, Position.CENTER)
        assertEquals(Position.CENTER, Store.config.value.items.single { it.id == id }.area)
    }
}
