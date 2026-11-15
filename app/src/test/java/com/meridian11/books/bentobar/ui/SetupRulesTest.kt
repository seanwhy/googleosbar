package com.meridian11.books.bentobar.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Setup's two rules of where things are said: when the window goes to Bar by itself, and where a refused permission is told. */
class SetupRulesTest {
    @Test fun theWindowSwitchesToBarWhenBentoBarComesOnWhileSetupIsShown() {
        assertTrue(SetupRules.switchesToBar(was = false, running = true, page = Page.SETUP))
    }

    @Test fun itSwitchesOnlyAtTheMomentItComesOn() {
        assertFalse("already on: someone who opens Setup stays there", SetupRules.switchesToBar(was = true, running = true, page = Page.SETUP))
        assertFalse("still off", SetupRules.switchesToBar(was = false, running = false, page = Page.SETUP))
        assertFalse("turned off", SetupRules.switchesToBar(was = true, running = false, page = Page.SETUP))
    }

    @Test fun noOtherPageIsLeft() {
        for (page in Page.entries.filter { it != Page.SETUP })
            assertFalse("$page", SetupRules.switchesToBar(was = false, running = true, page = page))
    }

    @Test fun aRefusedPermissionIsSaidInItsRowOnSetupAndByTheWindowElsewhere() {
        val notifications = "android.permission.POST_NOTIFICATIONS"
        val calendar = "android.permission.READ_CALENDAR"
        assertTrue(SetupRules.saidInItsRow(Page.SETUP, notifications))
        assertTrue(SetupRules.saidInItsRow(Page.SETUP, calendar))
        assertFalse("asked from an item's options", SetupRules.saidInItsRow(Page.BAR, calendar))
        assertFalse("Setup has no row for it", SetupRules.saidInItsRow(Page.SETUP, "android.permission.ACCESS_COARSE_LOCATION"))
    }

    @Test fun aCameraRefusedForGoodIsSaidInItsAccessRow() {
        // Its row is there whenever an item needs the camera, which is the only way it is asked for.
        val camera = "android.permission.CAMERA"
        assertTrue(SetupRules.saidInItsRow(Page.SETUP, camera))
        assertFalse("asked from Mirror's options or popup", SetupRules.saidInItsRow(Page.BAR, camera))
    }
}
