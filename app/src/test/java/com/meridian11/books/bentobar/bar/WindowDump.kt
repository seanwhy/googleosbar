package com.meridian11.books.bentobar.bar

/**
 * A window list in the form `./bento debug windows` prints it (`id:tTYPE:title:[left,top][right,bottom]`, the windows
 * joined by " | "), so a list from a device can be pasted into a test as it is.
 *
 * TYPE is AccessibilityWindowInfo's: 1 an application, 3 a system window, 4 an accessibility overlay. A title is only
 * ever asked whether it says "Screenshot", as `BarController.isScreenshotPreview` asks it.
 */
internal class WindowDump(text: String) {
    private val titles = HashMap<Int, String>()

    val listed: List<WindowFact> = text.split(" | ").map { w ->
        val (id, type, title, l, t, r, b) = LINE.matchEntire(w.trim())?.destructured ?: error("not a line of the windows dump: $w")
        titles[id.toInt()] = title
        val kind = when (type) {
            "1" -> WindowFact.Kind.APPLICATION
            "3" -> WindowFact.Kind.SYSTEM
            "4" -> WindowFact.Kind.OVERLAY
            else -> WindowFact.Kind.OTHER
        }
        WindowFact(id.toInt(), kind, Box(l.toInt(), t.toInt(), r.toInt(), b.toInt()))
    }

    /** The windows whose title was asked, in the order they were. */
    val asked = ArrayList<Int>()

    fun saysScreenshot(w: WindowFact): Boolean {
        asked += w.id
        return titles[w.id]?.contains("Screenshot", ignoreCase = true) == true
    }

    private companion object {
        val LINE = Regex("""^(-?\d+):t(\d+):(.*):\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]$""")
    }
}
