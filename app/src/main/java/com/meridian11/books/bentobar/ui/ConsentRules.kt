package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.data.Store

/**
 * When the accessibility disclosure comes up and when Turn on may go straight to Settings (Google Play's prominent
 * disclosure and consent: AGENTS.md). Pure, so the rule is tested (`ConsentRulesTest`). The questions about the service
 * are the edge's, and are asked only for an answer still to come.
 */
internal object ConsentRules {
    /** What Setup's Turn on does. */
    enum class TurnOn { OPEN_SETTINGS, ASK_FIRST }

    /**
     * Whether the disclosure comes up by itself, at the first opening: no answer yet, the service not running and not
     * named in the system's setting (just after an update, Android restarts the activity before it binds the service
     * again), and Advanced Protection not on (it turns off accessibility services like this one anyway).
     */
    fun asksOnFirstOpening(consent: Store.Consent, serviceOn: () -> Boolean, settingOn: () -> Boolean, advancedProtection: () -> Boolean): Boolean =
        consent == Store.Consent.NOT_ASKED && !serviceOn() && !settingOn() && !advancedProtection()

    /** Only an Agree opens Settings; a Decline or no answer shows the disclosure, so the answer can still change. */
    fun turnOn(consent: Store.Consent): TurnOn = if (consent == Store.Consent.AGREED) TurnOn.OPEN_SETTINGS else TurnOn.ASK_FIRST
}
