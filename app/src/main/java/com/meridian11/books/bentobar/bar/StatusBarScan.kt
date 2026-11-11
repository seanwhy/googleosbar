package com.meridian11.books.bentobar.bar

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors

/** Android's rectangle as a plain [Box], and back. */
internal fun Rect.box() = Box(left, top, right, bottom)
internal fun Box.rect() = Rect(left, top, right, bottom)

/**
 * Reads the accessibility tree into plain facts for [BarRules]. The status bar is the only window whose content is
 * read (SystemUI's status bar window, as [BarNode]s, which become a [BarSnapshot]); of any other window, only its kind,
 * its id and where it is ([WindowFact]).
 */
object StatusBarScan {
    /** What a scan brings back: the plain [snapshot], and the live spacer node (a cheap "did anything move?" check for [BarController]). */
    class Scanned(val snapshot: BarSnapshot, val spacerNode: AccessibilityNodeInfo?)

    /** What [BarRules] may know of [w]: its kind, its id and where it is. */
    fun fact(w: AccessibilityWindowInfo): WindowFact {
        val kind = when (w.type) {
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowFact.Kind.SYSTEM
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowFact.Kind.APPLICATION
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowFact.Kind.OVERLAY
            else -> WindowFact.Kind.OTHER
        }
        return WindowFact(w.id, kind, Rect().also { w.getBoundsInScreen(it) }.box())
    }

    fun scan(w: AccessibilityWindowInfo): Scanned {
        val bar = Rect().also { w.getBoundsInScreen(it) }.box()
        // One round trip: prefetch the (small, ~35 node) tree instead of an IPC per getChild().
        val root = w.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE) ?: return Scanned(BarRules.parse(w.id, bar, emptyList()), null)
        var spacerNode: AccessibilityNodeInfo? = null
        val nodes = ArrayList<BarNode>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty() && nodes.size < 400) {
            val (n, depth) = stack.removeLast()
            val id = n.viewIdResourceName.orEmpty()
            if (BarRules.isSpacer(id)) spacerNode = n
            nodes += BarNode(id, Rect().also { n.getBoundsInScreen(it) }.box(), depth, n.text?.toString().orEmpty(),
                described = !n.contentDescription.isNullOrEmpty(), clickable = n.isClickable, children = n.childCount)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it to depth + 1) }
        }
        return Scanned(BarRules.parse(w.id, bar, nodes), spacerNode)
    }

    /**
     * The screenshots' answers, on one thread for the whole process, never shut down: a controller that stops must not
     * leave an answer with nowhere to run, because running it is what closes the screenshot's buffer ([barColors]). A
     * result that arrives after stop() is then dropped on the main thread by [ColorReader]'s started check.
     */
    private val answers = Executors.newSingleThreadExecutor()

    /**
     * A reading: a screenshot of the status bar window only, its pixels read for [ColorReader]. [done] gets the colours
     * (null: the picture gave none), or the code of Android's refusal as its second argument.
     */
    fun shoot(service: AccessibilityService, s: BarSnapshot, done: (BarColors?, Int?) -> Unit) {
        service.takeScreenshotOfWindow(s.windowId, answers, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) { done(runCatching { barColors(r, s) }.getOrNull(), null) }
            override fun onFailure(code: Int) { done(null, code) }
        })
    }

    /** The pixels around the clock (the whole bar when no clock was found), as [BarPixels] reads them. */
    private fun barColors(r: AccessibilityService.ScreenshotResult, s: BarSnapshot): BarColors? {
        val hb = r.hardwareBuffer
        val bmp = try {
            Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.let { wrapped -> wrapped.copy(Bitmap.Config.ARGB_8888, false).also { wrapped.recycle() } }
        } finally { hb.close() }
        bmp ?: return null
        try {
            val area = (s.clock ?: s.bar).rect().apply { offset(-s.bar.left, -s.bar.top) }
            // A clock box outside the picture (the bar changed size since the scan): read all of it.
            if (!area.intersect(0, 0, bmp.width, bmp.height)) area.set(0, 0, bmp.width, bmp.height)
            val px = IntArray(area.width() * area.height())
            bmp.getPixels(px, 0, area.width(), area.left, area.top, area.width(), area.height())
            return BarPixels.colors(px, area.width())
        } finally { bmp.recycle() }
    }
}
