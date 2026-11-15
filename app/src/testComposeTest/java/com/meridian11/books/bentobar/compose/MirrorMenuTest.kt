package com.meridian11.books.bentobar.compose

import android.Manifest
import android.app.Application
import android.graphics.SurfaceTexture
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.bar.MenuKey
import com.meridian11.books.bentobar.bar.MenuSurface
import com.meridian11.books.bentobar.bar.MenuWindow
import com.meridian11.books.bentobar.bar.PopupCommand
import com.meridian11.books.bentobar.bar.PopupState
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Access
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.MenuHost
import com.meridian11.books.bentobar.items.MirrorCamera
import com.meridian11.books.bentobar.items.MirrorItem
import com.meridian11.books.bentobar.items.MirrorState
import com.meridian11.books.bentobar.ui.Setup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * The Mirror's camera is closed the moment its popup starts to close (Review Focus 3), whichever way it goes, in a real
 * popup window (`MenuWindow`, as the strip puts one on the screen), with a camera that counts. Presses outside the popup go
 * through the popup's own rules (`PopupState`) as the strip's do: a popup that stays is not closed by a press elsewhere.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class MirrorMenuTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    /** The application Env holds: the first test's in this process (`Env.init` keeps the first). */
    private val app get() = Env.app as Application
    private lateinit var window: MenuWindow
    private val popups = PopupState<MenuWindow>()
    private val item = ItemConfig("mirror-1", "mirror")
    /** What the strip says the next press outside the popup landed on: its own item, another one, or null for anywhere else. */
    private var hit: String? = null

    /** A camera that counts its opens and closes, and says [says] as it opens. */
    private class Counting(val says: MirrorState) : MirrorCamera {
        var opens = 0
        var closes = 0
        override fun open(onState: (MirrorState) -> Unit) { opens++; onState(says) }
        override fun show(texture: SurfaceTexture, onSize: (width: Int, height: Int) -> Unit) {}
        override fun close() { closes++ }
    }

    private var camera = Counting(MirrorState.SHOWING)
    private val cameraBefore = MirrorItem.camera
    private val setupBefore = Setup.state.value
    private var frameDelay: Duration? = null

    @Before fun env() {
        Env.init(context)
        // Animations as a device has them: the fold and the dissolve take their time, and the window stays up meanwhile.
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        frameDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Store.update { BarConfig(items = listOf(item)) }
        // The camera's permission given, as Setup has read it (and where Env asks: it keeps the first test's application).
        for (a in listOf(context, app)) shadowOf(a).grantPermissions(Manifest.permission.CAMERA)
        Setup.state.value = setupBefore.copy(access = setOf(Access.CAMERA))
        MirrorItem.camera = { camera }
    }

    @After fun back() {
        if (::window.isInitialized) window.close(now = true)
        MirrorItem.camera = cameraBefore
        Setup.state.value = setupBefore
        for (a in listOf(context, app)) shadowOf(a).denyPermissions(Manifest.permission.CAMERA)
        Store.update { BarConfig() }
        frameDelay?.let { ShadowChoreographer.setFrameDelay(it) }
    }

    /**
     * Compose's own clock for every composition the test makes, the popup's window's too. Without it the popup's window takes
     * the first test's frame clock, which no frame reaches in a later test, and a state the camera answers with is never drawn.
     */
    @get:Rule val rule = createComposeRule()

    /** Long enough for an opening or a fold to end, and for what the popup's content then has to draw again. */
    private fun idle(ms: Long = 1_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        rule.waitForIdle()
    }

    /** What the strip does with a press outside the popup (`MenuCoordinator.outside`), by the popup's own rules. */
    private fun outside() {
        when (popups.press(hit, SystemClock.uptimeMillis(), stays = MirrorItem.staysOpen)) {
            PopupCommand.Fold -> window.close()
            PopupCommand.Dissolve -> window.close(dissolve = true)
            PopupCommand.LetKeysGo -> window.keys(take = false)
            else -> Unit
        }
    }

    private fun inside() {
        if (popups.pressInside() == PopupCommand.TakeKeys) window.keys(take = true)
    }

    /** The Mirror's popup on the screen, its camera opened once, as the strip opens it: the host is the window's own. */
    private fun popup(says: MirrorState = MirrorState.SHOWING) {
        camera = Counting(says)
        window = MenuWindow(context, "BentoBar menu", onOutside = { outside() }, onEscape = { window.close() }, onInside = { inside() })
        val host = object : MenuHost {
            override fun close() = window.close()
            override fun openItemSettings(id: String) {}
            override fun afterClose(action: () -> Unit) {}
            override fun onClosing(action: () -> Unit) = window.onClosing(action)
        }
        val width = MirrorItem.menuWidthDp
        assertTrue("the window is up", window.show(0, 0, width, shadow = {}) {
            MenuSurface(width.dp, 600.dp, window.glass) { MirrorItem.menu!!(item, host) }
        })
        popups.opened(window, MenuKey.Item(item.id))
        idle()
        assertEquals("the camera opens with the popup", 1, camera.opens)
        assertEquals("and stays open while it is up", 0, camera.closes)
    }

    private fun pressOutside(on: String?) {
        hit = on
        val now = SystemClock.uptimeMillis()
        window.dialog.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0))
    }

    /** The popup's content as Compose has it, merged as a screen reader hears it. */
    private fun nodes(): List<SemanticsNode> {
        fun root(v: View): ViewRootForTest? = v as? ViewRootForTest ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { root(g.getChildAt(it)) } }
        fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)
        return all(root(window.dialog.window!!.decorView)!!.semanticsOwner.rootSemanticsNode)
    }

    private fun SemanticsNode.words(): List<String> = (config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())

    private fun said(): List<String> = nodes().flatMap { it.words() }

    /** A press on what says [label], as a click reaches it. */
    private fun press(label: String) {
        val node = nodes().firstOrNull { label in it.words() && it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue("nothing to press says \"$label\": ${said()}", node != null)
        node!!.config[SemanticsActions.OnClick].action!!.invoke()
    }

    @Test fun theCameraIsClosedWhenTheClosingStarts() {
        // Each way out: what does it, and whether the popup is still on its way out (folding, dissolving) when the call returns.
        val ways = listOf<Triple<String, Boolean, () -> Unit>>(
            Triple("its close button", true) { press(Env.str(R.string.mirror_close)) },
            Triple("Esc", true) {
                window.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                window.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            },
            Triple("a press on its item", true) { pressOutside(item.id) },
            Triple("another item's popup", true) { pressOutside("another-item") },
            Triple("the bar hiding", false) { window.close(now = true) },
        )
        for ((way, leaves, close) in ways) {
            popup()
            close()
            assertTrue("$way: the camera is closed by the time the closing call returned", camera.closes >= 1)
            assertEquals("$way: and that is before the popup has gone", leaves, window.isShowing)
            idle()
            assertFalse("$way: gone", window.isShowing)
            assertEquals("$way: one camera, never opened again", 1, camera.opens)
            popups.leave(then = false)
        }
    }

    @Test fun aPressElsewhereLeavesItOpenAndTheCameraOn() {
        popup()
        assertTrue("it opens with the keys", window.hasKeys)
        pressOutside(null)
        idle(2_000)
        assertTrue("it stays", window.isShowing)
        assertFalse("and gives the keys to the app below", window.hasKeys)
        assertEquals("the camera stays on", 0, camera.closes)
        // A press inside takes the keys again, and Esc then closes it, and its camera.
        val now = SystemClock.uptimeMillis()
        window.dialog.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        assertTrue(window.hasKeys)
        window.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        window.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
        assertTrue(camera.closes >= 1)
        assertEquals(1, camera.opens)
    }

    @Test fun aRefusedCameraSaysSoAndOffersTheSwitch() {
        popup(says = MirrorState.REFUSED)
        assertTrue("it says so: ${said()}", Env.str(R.string.mirror_refused) in said())
        assertFalse("no picture's place for a camera that won't show", Env.str(R.string.mirror_picture) in said())
        // The permission is given, so the entry leads where the camera's switch is (App info: `Accesses.open`).
        press(Env.str(R.string.mirror_camera_settings))
        assertTrue("the popup goes, and its camera with it", camera.closes >= 1)
        assertTrue("folding away", window.isShowing)
        idle()
        assertFalse(window.isShowing)
    }

    @Test fun withoutThePermissionNoCameraOpensAndItOffersIt() {
        Setup.state.value = setupBefore.copy(access = emptySet())
        camera = Counting(MirrorState.SHOWING)
        window = MenuWindow(context, "BentoBar menu", onOutside = {}, onEscape = { window.close() })
        val host = object : MenuHost {
            override fun close() = window.close()
            override fun openItemSettings(id: String) {}
            override fun afterClose(action: () -> Unit) {}
            override fun onClosing(action: () -> Unit) = window.onClosing(action)
        }
        val width = MirrorItem.menuWidthDp
        window.show(0, 0, width, shadow = {}) { MenuSurface(width.dp, 600.dp, window.glass) { MirrorItem.menu!!(item, host) } }
        idle()
        assertEquals(0, camera.opens)
        assertTrue(said().toString(), Env.str(R.string.access_camera_allow) in said())
        // Given while the popup is up (Setup reads it again): the camera opens then.
        Setup.state.value = setupBefore.copy(access = setOf(Access.CAMERA))
        idle()
        assertEquals(1, camera.opens)
        window.close()
        assertTrue(camera.closes >= 1)
    }
}
