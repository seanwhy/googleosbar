package com.meridian11.books.bentobar.bar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A Compose UI in a TYPE_ACCESSIBILITY_OVERLAY window: the only window type an app may put on
 * top of the status bar. It brings its own lifecycle, saved-state and view-model owners because
 * there's no Activity. [onOutside] fires for touches outside the window (menus close on it) and is
 * handed the touch, which says where it was when it fell on another of this app's windows. [onKey]
 * gets key events while the window has focus.
 */
class Overlay(
    private val context: Context,
    title: String,
    focusable: Boolean = false,
    touchable: Boolean = true,
    private val onOutside: ((MotionEvent) -> Unit)? = null,
    private val onKey: ((KeyEvent) -> Boolean)? = null,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val life = OverlayLifecycle(lifecycleRegistry)
    private val savedState = SavedStateRegistryController.create(this).apply { performRestore(null) }
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private var root: FrameLayout? = null
    private var compose: ComposeView? = null
    val shown get() = root != null

    val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP or Gravity.START
        flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            (if (onOutside != null) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0) or
            (if (!touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setTitle(title)
    }

    fun show(content: @Composable () -> Unit) {
        val existing = compose
        if (existing != null) { existing.setContent(content); return }
        if (!life.shown()) return // destroyed: a window added now would never be removed
        val cv = ComposeView(context).apply { setContent(content) }
        val frame = Frame(context).apply {
            setViewTreeLifecycleOwner(this@Overlay)
            setViewTreeViewModelStoreOwner(this@Overlay)
            setViewTreeSavedStateRegistryOwner(this@Overlay)
            addView(cv)
        }
        try {
            wm.addView(frame, params)
        } catch (e: RuntimeException) {
            // The service's window token went (Android unbinds it a moment before it says so): no window, and no crash. Not
            // shown, so the next show tries again.
            Log.w("BentoBar", "can't add the window ${params.title}: ${e.javaClass.simpleName}")
            cv.disposeComposition()
            life.hidden()
            return
        }
        compose = cv
        root = frame
        applied = Placement(params.x, params.y, params.width, params.height, params.gravity, params.flags)
    }

    /**
     * Shows or hides the window's content without removing the window: invisible, Android doesn't
     * show the window at all, while its content is still laid out (so it can report when it has
     * something to draw again).
     */
    fun setContentVisible(visible: Boolean) { root?.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE }

    /** What the window was last given: a [relayout] that changes nothing is skipped, or every look at the bar costs a layout pass. */
    private var applied: Placement? = null

    /** Applies changed [params] (position, size, flags). */
    fun relayout() {
        val r = root ?: return
        val now = Placement(params.x, params.y, params.width, params.height, params.gravity, params.flags)
        if (now == applied) return
        runCatching { wm.updateViewLayout(r, params) }.onSuccess { applied = now }
    }

    /** Hear about clicks outside this window ([onOutside]) only while it matters, e.g. while hidden items are out. */
    fun watchOutside(on: Boolean) {
        val f = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val flags = if (on) params.flags or f else params.flags and f.inv()
        if (flags != params.flags) { params.flags = flags; relayout() }
    }

    fun hide() {
        val r = root ?: return
        runCatching { wm.removeViewImmediate(r) }
        root = null
        compose = null
        applied = null
        life.hidden()
    }

    /** Takes the window down for good. Safe if it was never shown, and to call again. */
    fun destroy() {
        hide()
        viewModelStore.clear()
        life.destroy()
    }

    /** Screen position of the window's top-left corner. */
    fun locationOnScreen(): IntArray = IntArray(2).also { root?.getLocationOnScreen(it) }

    /** Everything of a window that [relayout] can change. */
    private data class Placement(val x: Int, val y: Int, val width: Int, val height: Int, val gravity: Int, val flags: Int)

    @SuppressLint("ViewConstructor")
    private inner class Frame(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutside?.invoke(ev); return true }
            return super.dispatchTouchEvent(ev)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (onKey?.invoke(event) == true) return true
            return super.dispatchKeyEvent(event)
        }
    }
}

/**
 * An [Overlay]'s lifecycle: RESUMED while its window is up, CREATED while it's hidden, DESTROYED
 * once it's done with. Every call is safe in every state. LifecycleRegistry alone isn't: it throws
 * on INITIALIZED -> DESTROYED (an overlay destroyed before it was ever shown: the strip, when the
 * service is unbound while the status bar is hidden) and on anything after DESTROYED. Kept apart
 * from the window so it can be unit-tested (`OverlayLifecycleTest`).
 */
internal class OverlayLifecycle(private val registry: LifecycleRegistry) {
    val destroyed get() = registry.currentState == Lifecycle.State.DESTROYED

    /** The window is going up. False once destroyed: there's no way back, so don't show it. */
    fun shown(): Boolean {
        if (destroyed) return false
        registry.currentState = Lifecycle.State.RESUMED
        return true
    }

    fun hidden() {
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        if (destroyed) return
        // Never shown: through CREATED first, the only way LifecycleRegistry lets a lifecycle end.
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
