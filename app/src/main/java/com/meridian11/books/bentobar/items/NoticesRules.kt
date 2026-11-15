package com.meridian11.books.bentobar.items

/**
 * What Notification icons makes of other apps' notifications (pure, `NoticesRulesTest`): which of them count at all, which
 * the bar shows, the popup's order, and the one that stands in the Live activity item. The listener ([NoticeAccess]) reads
 * Android's notifications into [Raw]s; everything after that is decided here.
 */
object NoticesRules {
    /**
     * One notification as the listener read it, before it is kept: [groupSummary] for the summary Android shows over a
     * group (its children are the notifications themselves), [progress] of [progressMax] where the app shows a bar.
     */
    data class Raw(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String?,
        val text: String?,
        val postedAt: Long,
        val ongoing: Boolean,
        val clearable: Boolean,
        val groupSummary: Boolean,
        val progress: Int,
        val progressMax: Int,
        val indeterminate: Boolean,
    ) {
        /** Another app's words and the user's own: never printed. */
        override fun toString() = "Raw(title of ${title?.length ?: 0}, text of ${text?.length ?: 0}, ongoing=$ongoing)"
    }

    /** How far [raw]'s bar has come, 0 to 1, where it says: a maximum above 0 and not indeterminate. */
    fun progress(raw: Raw): Float? =
        if (raw.progressMax > 0 && !raw.indeterminate) (raw.progress.toFloat() / raw.progressMax).coerceIn(0f, 1f) else null

    /** Leaves out BentoBar's own ([own], its package) and the summaries over groups, which only repeat their children. */
    fun kept(raws: List<Raw>, own: String): List<Raw> = raws.filter { it.pkg != own && !it.groupSummary }

    /** The popup's list: every one, the newest first (the order the listener gave stands among equals). */
    fun rows(notices: List<Notices.Notice>): List<Notices.Notice> = notices.sortedByDescending { it.postedAt }

    /**
     * What the bar counts, newest first: the ongoing ones (a download, a call, an app at work in the background) only when
     * [ongoing], since one of those would keep the item in the bar for as long as it runs.
     */
    fun counted(notices: List<Notices.Notice>, ongoing: Boolean): List<Notices.Notice> = rows(notices).filter { ongoing || !it.ongoing }

    /** One per app, its newest, the newest first: the icons the bar can show, in the order it shows them. */
    fun bar(notices: List<Notices.Notice>, ongoing: Boolean): List<Notices.Notice> = counted(notices, ongoing).distinctBy { it.pkg }

    /** "+2" after the icons when [apps] have notifications and only [shown] have room. */
    fun more(shown: Int, apps: Int): String? = if (apps > shown) "+${apps - shown}" else null

    /** What stands in the Live activity item: the newest ongoing one that has a progress (a download, a file being copied). */
    fun live(notices: List<Notices.Notice>): Notices.Notice? = notices.filter { it.ongoing && it.progress != null }.maxByOrNull { it.postedAt }
}
