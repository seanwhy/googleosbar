package com.meridian11.books.bentobar.tile

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.Caffeine
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.Timers
import com.meridian11.books.bentobar.ui.MainActivity
import com.meridian11.books.bentobar.util.Fmt

private fun TileService.openApp() {
    startActivityAndCollapse(PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
}

/** Shows or hides BentoBar's items, e.g. before presenting. */
class BarTile : TileService() {
    override fun onStartListening() { Env.init(this); refresh() }

    override fun onClick() {
        Env.init(this)
        if (!Env.running) { openApp(); return }
        Store.update { it.copy(enabled = !it.enabled) }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val running = Env.running
        val on = Store.config.value.enabled
        t.state = if (running && on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = getString(when { !running -> R.string.tile_set_up; on -> R.string.tile_shown; else -> R.string.tile_hidden })
        t.updateTile()
    }
}

/** Keeps the screen on until switched off (needs BentoBar, which owns the window). */
class AwakeTile : TileService() {
    override fun onStartListening() { Env.init(this); refresh() }

    override fun onClick() {
        Env.init(this)
        if (!Env.running) { openApp(); return }
        Caffeine.toggle(null)
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = Caffeine.active()
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = when {
            !Env.running -> getString(R.string.tile_set_up_bentobar)
            !on -> getString(R.string.common_off)
            Caffeine.until.value == Caffeine.FOREVER -> getString(R.string.common_on)
            else -> Fmt.duration(Caffeine.left(Caffeine.until.value))
        }
        t.updateTile()
    }
}

/** Starts, pauses and resumes BentoBar's timer (25 minutes unless one is running). */
class TimerTile : TileService() {
    override fun onStartListening() { Env.init(this); refresh() }

    override fun onClick() {
        Env.init(this)
        if (Timers.state.value == null) Timers.startTimer(25 * 60_000L) else Timers.toggle()
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val s = Timers.state.value
        t.state = if (s?.running == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = when {
            s == null -> resources.getQuantityString(R.plurals.common_minutes_short, 25, 25)
            s.mode == Timers.Mode.STOPWATCH -> Fmt.clock(Timers.elapsed(s), elapsed = true).let { if (s.running) it else getString(R.string.tile_paused, it) }
            else -> Fmt.clock(Timers.remaining(s)).let { getString(if (s.running) R.string.tile_left else R.string.tile_paused, it) }
        }
        t.updateTile()
    }
}
