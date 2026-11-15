package com.meridian11.books.bentobar.items

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.atomic.AtomicInteger

/**
 * Now playing's entry in Android's "Notification access" list, and nothing more. It exists for one
 * reason: Android tells an app what the media players are playing (their sessions: title, artist,
 * artwork) only if the user has turned that app on in this list. BentoBar wants the sessions and no
 * notifications. So the manifest entry asks for no notification type, marks all four types as never
 * wanted (Android's page for the listener shows them off and disabled), and tells Android not to
 * bind this service by itself. It reads nothing: a notification that reached it all the same would
 * only be counted ([received]), for the test that proves none does.
 *
 * Reading sessions needs the listener to be turned on, not to be running, so normally this class is
 * never even created. Should a device insist on a running listener, [NowPlaying] asks for it to be
 * bound once ([bind]) and lets it go again when no Now playing item needs it ([release]).
 *
 * Everything about Now playing's notification access is in this one file: Now playing still works without it (an
 * icon and the media keys), and without the manifest entry [granted] is simply never true. Notification icons has an
 * entry of its own, which does ask for notifications: [NoticeAccess], in its own file.
 */
class MediaAccess : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        // Bound though nobody asked (a device that ignores "don't bind by yourself"): leave at once.
        if (!NowPlaying.wantsListener()) { runCatching { requestUnbind() }; return }
        NowPlaying.listenerConnected()
    }

    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Never expected (the manifest asks for none). Counted, not read. */
    override fun onNotificationPosted(sbn: StatusBarNotification?) { received.incrementAndGet() }

    companion object {
        @Volatile private var instance: MediaAccess? = null

        /** How many notifications ever reached the listener in this process. It should stay 0. */
        val received = AtomicInteger()

        /** The listener is bound right now (only after [bind]). */
        val connected: Boolean get() = instance != null

        fun component(context: Context) = ComponentName(context, MediaAccess::class.java)

        /** BentoBar is turned on in Android's Notification access list. Asks the system: not for every frame. */
        fun granted(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component(context)) == true
        }.getOrDefault(false)

        /** Android's page for BentoBar's notification access, or the whole list where a device has no such page. */
        fun openSettings(context: Context) {
            val own = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
            if (!Env.launch(own, quiet = true)) Env.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        /** Asks Android to bind the listener (it must be turned on). Only for a device that shows sessions to a running listener alone. */
        fun bind(context: Context) { runCatching { requestRebind(component(context)) } }

        /** Lets a bound listener go again. */
        fun release() { instance?.let { runCatching { it.requestUnbind() } } }
    }
}
