package com.meridian11.books.bentobar.ui

import android.app.Activity
import android.os.Bundle
import com.meridian11.books.bentobar.bar.BarService
import com.meridian11.books.bentobar.items.Env

/**
 * "BentoBar menu" in the launcher: opens the bar's menu with every item in it, so a keyboard
 * shortcut bound to it in the system's shortcut settings (Action + / › Customize) reaches the bar
 * without a pointer. BentoBar doesn't read keys itself. Without the strip on screen it opens settings.
 */
class BarMenuActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Env.init(this)
        val opened = BarService.current?.controller()?.openBarMenu() == true
        if (!opened) MainActivity.open(this, null)
        finish()
    }
}
