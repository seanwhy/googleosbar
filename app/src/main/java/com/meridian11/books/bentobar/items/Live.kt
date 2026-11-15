package com.meridian11.books.bentobar.items

import androidx.compose.runtime.Immutable

/**
 * What one type says is happening right now, for the Live activity item ([ItemType.liveNow]): the highest [rank] stands,
 * and among equals the latest [since] (in the clock [ItemType.sample] gets). [icon] is a glyph of `Sym`, [text] the
 * words for the bar, [desc] what a screen reader and the popup say. [open], if any, is what a click on its row does.
 */
@Immutable
data class LiveNow(
    val rank: Int,
    val icon: String,
    val text: String,
    val desc: String,
    val tone: Tone = Tone.NORMAL,
    val since: Long = 0L,
    val open: (() -> Unit)? = null,
) {
    /** For logs and dumps: never the words themselves, which can be a meeting's title or another app's. */
    override fun toString() = "LiveNow(rank=$rank, text of ${text.length}, tone=$tone)"
}

/** Which of the things happening now goes first: a call before a timer that has rung, before a meeting, and so on. */
object LiveRank {
    const val CALL = 90
    const val TIMER_DONE = 85
    const val MEETING = 80
    const val TIMER = 70
    const val FOCUS = 65
    const val FLIGHT = 60
    const val PROGRESS = 50
    const val MEDIA = 20
}
