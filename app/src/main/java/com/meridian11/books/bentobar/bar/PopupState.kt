package com.meridian11.books.bentobar.bar

/** What a press or a click comes to. `BarController` carries it out; nothing here touches a window. */
internal sealed interface PopupCommand<out W> {
    /** Fold the open popup back into its item. */
    data object Fold : PopupCommand<Nothing>

    /** Dissolve it in place: another item's popup opens as it goes. */
    data object Dissolve : PopupCommand<Nothing>

    /** Open the popup: what is open dissolves while it unfolds. */
    data object Open : PopupCommand<Nothing>

    /** Turn [popup], which is folding away, round. If it can't any more, a new one opens, as for [Open]. */
    data class Reopen<W>(val popup: W) : PopupCommand<W>

    /** This click was spent by the press that closed the popup: nothing happens. */
    data object Spend : PopupCommand<Nothing>

    /** A popup that stays was pressed beside: it stays up, and gives the keys back to the app below. */
    data object LetKeysGo : PopupCommand<Nothing>

    /** It is pressed inside, having given the keys back: it takes them again. */
    data object TakeKeys : PopupCommand<Nothing>
}

/**
 * Which popup is up, which is still leaving, and whose click a press has spent. The windows are opaque here ([W] is a
 * `MenuWindow` in the app): their phases, blur and clock are `MenuWindow`'s. No Android types, so the cases the design
 * names are unit-tested (`PopupStateTest`).
 */
internal class PopupState<W : Any> {
    var open: W? = null
        private set
    var openKey: MenuKey? = null
        private set

    /** A popup still folding away, and its key: clicked again meanwhile, it turns round. */
    private var leaving: W? = null
    private var leavingKey: MenuKey? = null
    /** It is folding away so that something can run once it is off the screen (a screenshot): it doesn't turn round. */
    private var leavingThen = false

    /**
     * The popup a press on its own item just closed: that press's click, which reaches the strip after the popup heard
     * of the press, is spent (it would open the popup again). Only that one click, and only for a moment: a second
     * click turns the popup round while it is still folding away.
     */
    private var spentKey: MenuKey? = null
    private var spentAt = 0L

    /** The open popup stays when pressed beside, and a press beside it gave the keys back to the app below. */
    private var keysGiven = false

    /** A click on [key]'s item at [now]. */
    fun toggle(key: MenuKey, now: Long): PopupCommand<W> {
        if (openKey == key) return PopupCommand.Fold
        if (spentKey == key) {
            spentKey = null
            if (now - spentAt < SPENT_MS) return PopupCommand.Spend
        }
        spentKey = null
        val back = leaving
        if (open == null && !leavingThen && leavingKey == key && back != null) return PopupCommand.Reopen(back)
        return PopupCommand.Open
    }

    /**
     * A press outside the open popup at [now], on what the strip calls [hit] (null: anywhere else, a press on another
     * app's window comes without coordinates). Null with no popup open: there is nothing to do. A popup that [stays]
     * (its type's `staysOpen`: a mirror, a prompter) is not closed by a press anywhere else: it gives the keys back to
     * the app below instead. A press on its own item or on another item is as for any popup.
     */
    fun press(hit: String?, now: Long, stays: Boolean = false): PopupCommand<W>? {
        val key = openKey ?: return null
        return when (hit) {
            null -> if (stays) { keysGiven = true; PopupCommand.LetKeysGo } else PopupCommand.Fold
            key.owner -> {
                spentKey = key
                spentAt = now
                PopupCommand.Fold
            }
            else -> PopupCommand.Dissolve
        }
    }

    /** A press inside the open popup: one that gave the keys back takes them again. Null: nothing to do. */
    fun pressInside(): PopupCommand<W>? {
        if (open == null || !keysGiven) return null
        keysGiven = false
        return PopupCommand.TakeKeys
    }

    /** [window] is up for [key], with the keys. */
    fun opened(window: W, key: MenuKey) {
        open = window
        openKey = key
        keysGiven = false
    }

    /** [window], which was folding away, turned round. */
    fun reopened(window: W, key: MenuKey) {
        leaving = null
        leavingKey = null
        opened(window, key)
    }

    /**
     * The open popup starts to leave ([then]: so that something can run once it is off the screen). Returns the popup
     * still leaving from before, to be taken away at once: only one popup is ever folding away.
     */
    fun leave(then: Boolean): W? {
        val earlier = leaving
        leaving = open
        leavingKey = openKey
        leavingThen = then
        open = null
        openKey = null
        keysGiven = false
        return earlier
    }

    /** With nothing open, the popup still leaving goes at once: returns it. */
    fun dropLeaving(): W? = leaving.also { forgetLeaving() }

    /**
     * The popup still leaving goes at once if it is [key]'s, the one key whose click would turn it round: returns it.
     * One leaving for another key stays, and goes on leaving.
     */
    fun dropLeaving(key: MenuKey): W? = if (leavingKey == key) dropLeaving() else null

    /** [window] is off the screen. Only the one still leaving is forgotten: an older popup's end says nothing of a newer one. */
    fun gone(window: W) {
        if (leaving === window) forgetLeaving()
    }

    private fun forgetLeaving() {
        leaving = null
        leavingKey = null
        leavingThen = false
    }

    private companion object {
        const val SPENT_MS = 1_000L
    }
}
