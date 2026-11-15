package com.meridian11.books.bentobar.items

import android.app.PendingIntent
import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Other apps' notifications as plain values, while Notification icons' listener ([NoticeAccess]) is bound and the item
 * shows: in memory only, never kept, never logged, never sent. The listener publishes the whole list each time it
 * changes, and an empty one when it goes.
 */
object Notices {
    /**
     * One notification: [key] is Android's, [app] the posting app's name, [progress] 0 to 1 where it has one, [open] what
     * its click sends. Its words are another app's and the user's own: [toString] never says them.
     */
    @Immutable
    data class Notice(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String?,
        val text: String?,
        val postedAt: Long,
        val ongoing: Boolean,
        val clearable: Boolean,
        val progress: Float?,
        val icon: Bitmap?,
        val open: PendingIntent?,
    ) {
        override fun toString() = "Notice(title of ${title?.length ?: 0}, text of ${text?.length ?: 0}, ongoing=$ongoing)"
    }

    private val held = MutableStateFlow<List<Notice>>(emptyList())

    /** What is up now, in the order the listener gave it. Empty while nothing listens. */
    val now: StateFlow<List<Notice>> get() = held

    /** The listener's whole list, in place of the one before; empty when it goes. */
    internal fun publish(list: List<Notice>) {
        held.value = list
    }
}
