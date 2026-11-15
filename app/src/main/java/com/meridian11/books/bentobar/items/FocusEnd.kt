package com.meridian11.books.bentobar.items

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * A Focus session's end at its time: the alarm BentoBar sets for it ([FocusAlarm]), and nothing else (the receiver is not
 * exported). The process may have been started for it, so the app is set up first, and the receiver is held until the
 * kept session has been read and ended.
 */
class FocusEnd : BroadcastReceiver() {
    companion object {
        const val END = "com.meridian11.books.bentobar.focus.END"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != END) return
        val pending = goAsync()
        try {
            Env.init(context)
            FocusItem.alarmRang { pending.finish() }
        } catch (e: Exception) {
            Log.w(Env.TAG, "Focus alarm failed: ${e.javaClass.simpleName}")
            pending.finish()
        }
    }
}
