package com.meridian11.books.bentobar.compose

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.bar.MenuSurface
import com.meridian11.books.bentobar.bar.MenuWindow
import com.meridian11.books.bentobar.items.Env
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * What a popup's content asks to end as the popup starts to close (`MenuHost.onClosing`: a mirror's camera) ends once,
 * by the time the closing call has returned, whichever way the popup goes: its own button and a press on its item fold
 * it, Esc does what its button does, another item's popup dissolves it, the bar hiding takes it away at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class MenuWindowClosingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private var ran = 0
    private var insides = 0
    private lateinit var window: MenuWindow

    private var frameDelay: Duration? = null

    /** Frames as a display gives them, not one a millisecond: the opening and the fold run on frames. */
    @Before fun env() {
        Env.init(context)
        // Animations as a device has them: the fold and the dissolve take their time, and the window stays up meanwhile.
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        frameDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After fun frames() { frameDelay?.let { ShadowChoreographer.setFrameDelay(it) } }

    /** Long enough for an opening or a fold to end. */
    private fun idle(ms: Long = 1_000) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** A popup up on the screen whose content asks, once, as a mirror's does, for something to end as it starts to close. */
    private fun popup() {
        window = MenuWindow(context, "test", onOutside = {}, onEscape = { window.close() }, onInside = { insides++ })
        assertTrue("the window is up", window.show(0, 0, 300, shadow = {}) {
            MenuSurface(300.dp, 600.dp, window.glass) {
                DisposableEffect(Unit) { window.onClosing { ran++ }; onDispose {} }
                Text("popup")
            }
        })
        idle()
        assertEquals("nothing ran while it is up", 0, ran)
    }

    /**
     * [close] closes the popup one way: what it asked for has run once by then, while the popup is still on its way out
     * where it [leaves] (folding, dissolving), and once after it has gone.
     */
    private fun once(way: String, leaves: Boolean, close: () -> Unit) {
        popup()
        close()
        assertEquals("$way: by the time the closing call returned", 1, ran)
        assertEquals("$way: still on its way out", leaves, window.isShowing)
        idle()
        assertFalse("$way: gone", window.isShowing)
        window.close()
        assertEquals("$way: once", 1, ran)
    }

    @Test fun itsOwnButtonOrAPressOnItsItemFoldsItAndItRunsOnce() = once("fold", leaves = true) { window.close() }

    @Test fun escapeClosesItAndItRunsOnce() = once("Esc", leaves = true) {
        window.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        window.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
    }

    @Test fun anotherPopupDissolvesItAndItRunsOnce() = once("dissolve", leaves = true) { window.close(dissolve = true) }

    @Test fun theBarHidingTakesItAwayAtOnceAndItRunsOnce() = once("at once", leaves = false) { window.close(now = true) }

    @Test fun aPopupThatGaveSomethingUpIsNeverTurnedRound() {
        popup()
        window.close()
        assertTrue("folding", window.isShowing)
        assertFalse("a click while it folds opens a new one", window.reopen())
    }

    @Test fun aPopupThatGaveNothingUpStillTurnsRound() {
        window = MenuWindow(context, "test", onOutside = {}, onEscape = {})
        assertTrue(window.show(0, 0, 300, shadow = {}) { MenuSurface(300.dp, 600.dp, window.glass) { Text("popup") } })
        idle()
        window.close()
        assertTrue("as before: it turns round", window.reopen())
        window.close(now = true)
    }

    @Test fun whatIsAskedForOnceTheClosingHasBegunRunsAtOnce() {
        popup()
        window.close()
        window.onClosing { ran += 10 }
        assertEquals(11, ran)
    }

    @Test fun aPopupThatStaysGivesTheKeysBackAndHearsAPressInside() {
        popup()
        assertTrue("it opens with the keys", window.hasKeys)
        window.keys(take = false)
        assertFalse("given back", window.hasKeys)
        assertTrue("still up", window.isShowing)
        val now = SystemClock.uptimeMillis()
        window.dialog.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        assertEquals("a press inside is heard", 1, insides)
        window.keys(take = true)
        assertTrue("taken again", window.hasKeys)
        window.close()
        window.keys(take = true)
        assertFalse("a leaving popup takes nothing", window.hasKeys)
        assertEquals(1, ran)
    }
}
