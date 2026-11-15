package com.meridian11.books.bentobar.compose

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.LiveRank
import com.meridian11.books.bentobar.items.NoticeIcons
import com.meridian11.books.bentobar.items.NoticesItem
import com.meridian11.books.bentobar.items.NoticesRules
import com.meridian11.books.bentobar.util.Sym
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What Notification icons draws in the bar, on real pictures: the apps' icons as one strip as wide as they are, "+3"
 * for the apps it has no room for, a picture drawn again only when the icons change, and what its line in the Live
 * activity item says. Staged, since no listener runs here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class NoticesBarTest {
    @Before fun env() = Env.init(ApplicationProvider.getApplicationContext<Application>())
    @After fun off() { NoticesItem.debug(listOf("off")) }

    private val item = ItemConfig("n", NoticesItem.type)

    private fun stage(name: String) = assertNotNull(NoticesItem.debug(listOf("stage", name)))

    @Test fun theBarShowsOneIconPerAppAsOneStripAndCountsTheRest() {
        stage("many")
        val apps = NoticesRules.bar(NoticesItem.shown(), ongoing = false).size
        assertTrue("the stage has more apps than room: $apps", apps > 4)
        val s = NoticesItem.state(item)
        assertTrue(s.active)
        assertTrue("small icons are masks: they take the strip's colour", s.tintImage)
        val strip = s.image!!
        assertEquals("four icons and three gaps", NoticeIcons.width(4), strip.width)
        assertEquals(NoticeIcons.PX, strip.height)
        assertEquals("+${apps - 4}", s.text)
        // With room for all of them, no count.
        val roomy = NoticesItem.state(NoticesItem.limit.set(item, 8))
        assertEquals(NoticeIcons.width(apps), roomy.image!!.width)
        assertNull(roomy.text)
    }

    @Test fun theSamePicturesAreTheSameStripSoTheBarHasNothingToDrawAnew() {
        stage("three")
        val first = NoticesItem.state(item).image
        assertSame(first, NoticesItem.state(item).image)
        assertEquals("and an unchanged state is an equal one", NoticesItem.state(item), NoticesItem.state(item))
    }

    @Test fun theBarSaysWhichAppsNeverWhat() {
        stage("three")
        val s = NoticesItem.state(item)
        for (n in NoticesItem.shown()) {
            assertTrue("${s.desc} names ${n.app}", n.app in s.desc)
            for (w in listOfNotNull(n.title, n.text)) assertFalse("${s.desc} says \"$w\"", w in s.desc)
        }
    }

    @Test fun nothingToSayIsNotActiveAndNoAccessSaysSo() {
        stage("none")
        NoticesItem.state(item).let { assertFalse(it.active); assertNull(it.image); assertEquals(Sym.NOTIFICATIONS, it.icon) }
        stage("noaccess")
        NoticesItem.state(item).let { assertFalse(it.active); assertNull(it.image); assertEquals(Sym.NOTIFICATIONS_OFF, it.icon) }
    }

    @Test fun anOngoingOneCountsInTheBarOnlyWhenAsked() {
        stage("progress")
        assertEquals("the chat only: the download is ongoing", NoticeIcons.width(1), NoticesItem.state(item).image!!.width)
        assertEquals(NoticeIcons.width(2), NoticesItem.state(NoticesItem.ongoing.set(item, true)).image!!.width)
    }

    @Test fun anotherAppsProgressStandsInTheLiveActivityItem() {
        stage("progress")
        val live = NoticesItem.liveNow(0L, emptyList())!!
        assertEquals(LiveRank.PROGRESS, live.rank)
        assertEquals("Files 62%", live.text)
        assertNotNull("a click on its row opens it", live.open)
        stage("three")
        assertNull("nothing under way", NoticesItem.liveNow(0L, emptyList()))
    }
}
