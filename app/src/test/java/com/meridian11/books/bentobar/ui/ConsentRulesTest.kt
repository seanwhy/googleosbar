package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.data.Store.Consent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the accessibility disclosure comes up: the consent gate, from AGENTS.md's finding about Google Play's disclosure. */
class ConsentRulesTest {
    private fun asks(consent: Consent, serviceOn: Boolean = false, settingOn: Boolean = false, advancedProtection: Boolean = false) =
        ConsentRules.asksOnFirstOpening(consent, { serviceOn }, { settingOn }, { advancedProtection })

    @Test fun googlePlayWantsAProminentDisclosureWithConsent_itComesUpOnFirstOpening() {
        assertTrue(asks(Consent.NOT_ASKED))
    }

    @Test fun googlePlayWantsAProminentDisclosureWithConsent_turnOnOpensSettingsOnlyAfterAnAgree() {
        assertEquals(ConsentRules.TurnOn.OPEN_SETTINGS, ConsentRules.turnOn(Consent.AGREED))
        assertEquals("no answer yet", ConsentRules.TurnOn.ASK_FIRST, ConsentRules.turnOn(Consent.NOT_ASKED))
        assertEquals("a Decline can be changed, through the disclosure", ConsentRules.TurnOn.ASK_FIRST, ConsentRules.turnOn(Consent.DECLINED))
    }

    @Test fun onceAnsweredOnlyTurnOnBringsItBack() {
        assertFalse(asks(Consent.AGREED))
        assertFalse(asks(Consent.DECLINED))
    }

    @Test fun theServiceAlreadyOnNeedsNoDisclosure() {
        assertFalse(asks(Consent.NOT_ASKED, serviceOn = true))
    }

    @Test fun afterAnUpdateTheSettingStillNamesAServiceNotBoundYet() {
        // Device findings: the activity restarts before Android binds the service again; the list is empty for a moment, the setting isn't.
        assertFalse(asks(Consent.NOT_ASKED, settingOn = true))
    }

    @Test fun advancedProtectionForbidsAccessibilityToolsAnyway() {
        assertFalse(asks(Consent.NOT_ASKED, advancedProtection = true))
    }

    @Test fun theServiceIsNotAskedAboutOnceThereIsAnAnswer() {
        var asked = 0
        val ask = { asked++; false }
        assertFalse(ConsentRules.asksOnFirstOpening(Consent.AGREED, ask, ask, ask))
        assertEquals(0, asked)
    }
}
