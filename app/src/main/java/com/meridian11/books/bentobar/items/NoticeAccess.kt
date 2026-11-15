package com.meridian11.books.bentobar.items

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Notification icons' entry in Android's "Notification access" list, beside [MediaAccess] and under a name of its own,
 * so the user gives each its access by itself and Now playing's promise (no notifications at all) stays true. It asks
 * for every kind of notification, and Android does not bind it by itself: [NoticesItem] asks for it ([bind]) while it
 * is live and lets it go ([release]) when it goes idle, so no listener exists while nothing shows the item.
 *
 * Bound, it keeps Android's notifications by key while it runs, reads them into plain values on a thread of its own
 * (the words, the app, a progress, the small icon as a picture, what a click sends) and hands the whole list to
 * [Notices]; unbound or let go, it forgets them and hands over an empty one. Nothing is written, logged or sent.
 */
class NoticeAccess : NotificationListenerService() {
    /** What is up now, by Android's key, as Android gave it. Main thread (the listener's callbacks come there). */
    private val posted = LinkedHashMap<String, StatusBarNotification>()
    /** Counts each forgetting: a reading that began before one is dropped when it is done. Main thread. */
    private var generation = 0
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val rebuild = Runnable { read() }

    override fun onListenerConnected() {
        // Bound though nobody asked (the access was just turned on, or the process died while it was bound): leave at
        // once, before a single notification is read.
        if (!NoticesItem.wantsListener()) { runCatching { requestUnbind() }; return }
        instance = this
        posted.clear()
        runCatching { activeNotifications }.getOrNull()?.forEach { sbn -> sbn?.let { posted[it.key] = it } }
        changed()
    }

    override fun onListenerDisconnected() { if (instance === this) { instance = null; forget() } }

    override fun onDestroy() {
        if (instance === this) { instance = null; forget() }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (instance !== this || sbn == null) return
        posted[sbn.key] = sbn
        changed()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (instance !== this || sbn == null) return
        if (posted.remove(sbn.key) != null) changed()
    }

    /** One reading for however many callbacks came at once (a download's progress comes several times a second). */
    private fun changed() {
        main.removeCallbacks(rebuild)
        main.post(rebuild)
    }

    /**
     * Reads what is up into plain values on the listener's thread (another app's icon and name are slow to load), and
     * hands them over on the main thread, unless the listener was let go or forgot in between.
     */
    private fun read() {
        if (instance !== this) return
        val now = posted.values.toList()
        val asOf = generation
        val own = packageName
        work.execute {
            val notices = runCatching { Reading.notices(this, now, own) }.getOrDefault(emptyList())
            main.post {
                if (instance === this && asOf == generation && NoticesItem.wantsListener()) {
                    Notices.publish(notices)
                    Ticker.refresh(NoticesItem.type)
                }
            }
        }
    }

    /** No notification of another app's stays: the list here, the one in [Notices], and what the reading thread holds. */
    private fun forget() {
        posted.clear()
        generation++
        main.removeCallbacks(rebuild)
        Notices.publish(emptyList())
        work.execute { Reading.forget() }
    }

    /** A click on [key] from BentoBar: the notification goes as a click in Android's own list would take it, where the app asked for that. */
    private fun clicked(key: String) {
        val sbn = posted[key] ?: return
        if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) runCatching { cancelNotification(key) }
    }

    /** The reading thread's part: what a notification says, as plain values. Only the reading thread touches it. */
    private object Reading {
        /** The most of a title or a text that is kept: the popup shows one line of each. */
        private const val CHARS = 200
        /** Small icons by app and resource, drawn once: a notification that updates its progress keeps its picture. */
        private val icons = HashMap<String, Bitmap?>()
        private val labels = HashMap<String, String>()

        fun notices(context: Context, now: List<StatusBarNotification>, own: String): List<Notices.Notice> {
            val raws = now.map { raw(context, it) }
            val byKey = now.associateBy { it.key }
            return NoticesRules.kept(raws, own).map { r ->
                val n = byKey.getValue(r.key).notification
                Notices.Notice(r.key, r.pkg, r.app, r.title, r.text, r.postedAt, r.ongoing, r.clearable, NoticesRules.progress(r),
                    icon(context, r.pkg, n.smallIcon), n.contentIntent)
            }
        }

        private fun raw(context: Context, sbn: StatusBarNotification): NoticesRules.Raw {
            val n = sbn.notification
            val extras = n.extras
            fun words(key: String) = runCatching { extras?.getCharSequence(key)?.toString() }.getOrNull()
                ?.let { TextRules.oneLine(it, CHARS) }?.takeIf { it.isNotEmpty() }
            fun number(key: String) = runCatching { extras?.getInt(key) }.getOrNull() ?: 0
            return NoticesRules.Raw(
                key = sbn.key, pkg = sbn.packageName, app = label(context, sbn.packageName),
                title = words(Notification.EXTRA_TITLE) ?: words(Notification.EXTRA_TITLE_BIG),
                text = words(Notification.EXTRA_TEXT) ?: words(Notification.EXTRA_BIG_TEXT),
                postedAt = sbn.postTime, ongoing = sbn.isOngoing, clearable = sbn.isClearable,
                groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                progress = number(Notification.EXTRA_PROGRESS), progressMax = number(Notification.EXTRA_PROGRESS_MAX),
                indeterminate = runCatching { extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) }.getOrNull() == true,
            )
        }

        /** A resource icon is drawn once per app and resource; a picture an app hands over is drawn each time it comes. */
        private fun icon(context: Context, pkg: String, icon: Icon?): Bitmap? {
            val key = icon?.takeIf { it.type == Icon.TYPE_RESOURCE }?.let { runCatching { "$pkg/${it.resPackage}/${it.resId}" }.getOrNull() }
                ?: return NoticeIcons.mask(context, icon)
            return icons.getOrPut(key) { NoticeIcons.mask(context, icon) }
        }

        /** The app's name, or its package where Android doesn't show BentoBar the app. */
        private fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
            runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
        }

        fun forget() {
            icons.clear()
            labels.clear()
        }
    }

    companion object {
        @Volatile private var instance: NoticeAccess? = null

        /** For the slow part of a reading: other apps' names and icons. One thread, so readings come out in order. */
        private val work: Executor by lazy {
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
                Thread(r, "BentoBar-notices").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            }
        }

        fun component(context: Context) = ComponentName(context, NoticeAccess::class.java)

        /** Turned on in Android's Notification access list. Asks the system: not for every frame. */
        fun granted(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component(context)) == true
        }.getOrDefault(false)

        /** Android's page for this entry, or the whole list where a device has no such page. */
        fun openSettings(context: Context) {
            val own = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
            if (!Env.launch(own, quiet = true)) Env.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        /** The listener is bound and reading right now. */
        val connected: Boolean get() = instance != null

        /** Asks Android to bind the listener (only once the access is given; Android refuses it otherwise). */
        fun bind(context: Context) { runCatching { requestRebind(component(context)) } }

        /** Lets the listener go, and with it everything it read. A listener on its way in leaves when it arrives ([onListenerConnected]). Main thread. */
        fun release() {
            val listener = instance ?: return
            instance = null
            listener.forget()
            runCatching { listener.requestUnbind() }
        }

        /** Dismisses [key], as its × in Android's own list does. Only what Android lets go of goes. */
        fun dismiss(key: String) { instance?.let { runCatching { it.cancelNotification(key) } } }

        /** Clears every notification Android lets the user clear, as "Clear all" in its own list does. */
        fun clearAll() { instance?.let { runCatching { it.cancelAllNotifications() } } }

        /**
         * Opens [notice] as a click on it in Android's list does: what the app gave it to send, else the app's own window.
         * False when neither went; never throws. Android starts another app's window from the bar only if asked to in so many words.
         */
        @Suppress("DEPRECATION") // the mode's newer name exists from Android 16 on; this one works from 14
        fun open(context: Context, notice: Notices.Notice): Boolean {
            val sent = notice.open?.let { pending ->
                runCatching {
                    val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pending.send(context, 0, null, null, null, null, options.toBundle())
                }.isSuccess
            } == true
            if (sent) { instance?.clicked(notice.key); return true }
            val launch = runCatching { context.packageManager.getLaunchIntentForPackage(notice.pkg) }.getOrNull() ?: return false
            // Not through Env.launch: its line for a failure names the intent, and so the app that had something to say.
            return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }
}
