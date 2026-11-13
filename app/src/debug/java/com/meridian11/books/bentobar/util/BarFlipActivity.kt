package com.meridian11.books.bentobar.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.meridian11.books.bentobar.bar.BarService

/**
 * A window for colour tests, in debug builds only (src/debug). It lies under the status bar and turns
 * the bar's icons dark or light on command, in the same window and with no event of any kind: the way
 * the bar changes its look on a device, where nothing can be made to do it at a set moment.
 *
 *   adb shell am start -n com.meridian11.books.bentobar/.util.BarFlipActivity --ez dark true
 *   … --ez dark false --ei after 1500     the icons turn light 1.5 s from now
 *   … --ez dark true --ei unlock 3000     from screen off the icons are light (a lock screen's look) and
 *                                         stay light until 3 s after the unlock, then turn dark
 *   … --ez close true                     closes the window (on a desktop the Back key doesn't)
 *   … --ez hidebars true                  hides the system bars, as a full-screen video does, so that the bar leaves
 *                                         with no other app on screen; false shows them again. Needs a full-screen window
 *
 * Only the shell can start it (android.permission.DUMP, like [DebugReceiver]).
 */
class BarFlipActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var dark = true
    private var unlockMs = -1

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (unlockMs < 0) return
            main.removeCallbacksAndMessages(null)
            if (i.action == Intent.ACTION_SCREEN_OFF) icons(!dark, "screen off")
            else main.postDelayed({ icons(dark, "$unlockMs ms after the unlock") }, unlockMs.toLong())
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setDecorFitsSystemWindows(false)
        // A mid grey: light and dark icons both stand out on it in a recording.
        window.setBackgroundDrawable(ColorDrawable(Color.rgb(0x8A, 0x8F, 0x98)))
        window.statusBarColor = Color.TRANSPARENT
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) })
        read(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // a window made anew (a resize) reads the latest command, not the first
        read(intent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(screen)
        super.onDestroy()
    }

    private fun read(i: Intent) {
        main.removeCallbacksAndMessages(null)
        if (i.getBooleanExtra("close", false)) return finishAndRemoveTask()
        if (i.hasExtra("hidebars")) return bars(i.getBooleanExtra("hidebars", false))
        val to = i.getBooleanExtra("dark", true)
        unlockMs = i.getIntExtra("unlock", -1)
        val after = i.getIntExtra("after", 0)
        if (unlockMs >= 0 || after <= 0) { dark = to; icons(to, "now") }
        else main.postDelayed({ dark = to; icons(to, "after $after ms") }, after.toLong())
    }

    private fun bars(hide: Boolean) {
        val all = WindowInsets.Type.systemBars()
        val controller = window.decorView.windowInsetsController
        if (hide) controller?.hide(all) else controller?.show(all)
        Log.i(BarService.TAG, "bar flip: system bars ${if (hide) "hidden" else "shown"}")
    }

    private fun icons(dark: Boolean, why: String) {
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        // Through the decor view: the window has no controller before its decor view exists.
        window.decorView.windowInsetsController?.setSystemBarsAppearance(if (dark) light else 0, light)
        Log.i(BarService.TAG, "bar flip: ${if (dark) "dark" else "light"} icons ($why)")
    }
}
