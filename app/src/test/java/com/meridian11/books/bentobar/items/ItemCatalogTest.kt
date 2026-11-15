package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.SourceScan
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.ui.onlineRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whatever a new item type declares is checked here against what the screens and the ticker do with it. A type forgotten by hand can't pass. */
class ItemCatalogTest {
    @Test fun everyItemTypeInTheSourcesIsRegistered() {
        // "One file and one registration": an `object … : ItemType(` that isn't in Items.all could never be added.
        val declared = SourceScan.sources.flatMap { f ->
            Regex("""object (\w+) : ItemType\(""").findAll(SourceScan.code(f)).map { it.groupValues[1] }.toList()
        }.toSet()
        assertEquals(declared, Items.all.map { it::class.simpleName!! }.toSet())
        assertEquals("no type registered twice", Items.all.size, Items.all.map { it.type }.toSet().size)
    }

    @Test fun addListsEveryTypeOnceAndInOneGroup() {
        val listed = ItemGroup.entries.flatMap { Items.inGroup(it) }
        assertEquals(Items.all.toSet(), listed.toSet())
        assertEquals(Items.all.size, listed.size)
        for (group in ItemGroup.entries) assertTrue(group.name, Items.inGroup(group).isNotEmpty())
    }

    @Test fun eachGroupIsOneBlockOfTheRegistry() {
        // Items.all is written one block per group, each under its comment, in the groups' order: a type added outside its
        // group's block would make the comments untrue.
        val runs = Items.all.map { it.group }.fold(emptyList<ItemGroup>()) { acc, g -> if (acc.lastOrNull() == g) acc else acc + g }
        assertEquals(ItemGroup.entries.toList(), runs)
    }

    @Test fun addKeepsTheOnlineGroupForExactlyTheTypesThatGoOnline() {
        assertEquals(Items.all.filter { it.online != null }, Items.inGroup(ItemGroup.ONLINE))
        for (type in Items.inGroup(ItemGroup.ONLINE)) assertTrue(type.type, Items.of(type.type)?.online != null)
    }

    @Test fun everyTypeThatGoesOnlineSaysWhatItSendsInAbout() {
        val online = Items.all.filter { it.online != null }
        for (type in online) assertTrue("${type.type} has no line in About", type.onlineNote != null)
        assertEquals("one line each", online.size, online.mapNotNull { it.onlineNote }.toSet().size)
    }

    @Test fun everyOnlineServiceHasItsOwnSetupRowAndAnItem() {
        val rows = Online.Service.entries.map(::onlineRow)
        assertEquals(rows.size, rows.toSet().size)
        for (row in rows) assertTrue(row.label != 0 && row.first != 0)
        for (service in Online.Service.entries) {
            assertTrue("$service: no item uses it", Items.all.any { it.online == service })
            assertEquals("$service: a key needs a way to remove it", service.needsKey, onlineRow(service).removeKey != null)
        }
    }

    @Test fun holdingAndPresentingAreOnTheTypes() {
        assertEquals(setOf("network", "cpu", "memory"), Items.all.filter { it.holds }.map { it.type }.toSet())
        assertEquals(setOf("timer", "countdown", "event"), Items.all.filter { it.presents }.map { it.type }.toSet())
        assertEquals(setOf("weather", "air"), Items.all.filter { it.usesMyLocation }.map { it.type }.toSet())
    }

    @Test fun everyAccessATypeNeedsHasWords() {
        // Setup's row and the item's line are drawn from these: an access with no words would be a blank row.
        for (type in Items.all) for (access in type.needs) {
            assertTrue("${type.type} needs $access, which has no title", Accesses.title(access) != 0)
            assertTrue("${type.type} needs $access, which has no explanation", Accesses.explain(access) != 0)
            assertTrue("${type.type} needs $access, which has no button", Accesses.allow(access) != 0)
        }
    }

    @Test fun everySamplerNamedIsARealType() {
        val names = Regex(""""(\w+)" in types""").findAll(SourceScan.code(SourceScan.sources.single { it.name == "Env.kt" })).map { it.groupValues[1] }.toSet()
        assertTrue(names.isNotEmpty())
        val types = Items.all.map { it.type }.toSet()
        assertEquals(emptySet<String>(), names - types)
        for (type in Items.all) assertEquals(type.type, emptySet<String>(), type.samples - types)
    }
}
