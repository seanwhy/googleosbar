package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The popup cases the design names. The windows are strings here; a window's own phases are MenuWindow's. */
class PopupStateTest {
    private val popups = PopupState<String>()
    private val a = MenuKey.Item("a")
    private val b = MenuKey.Item("b")

    private fun open(key: MenuKey, window: String, at: Long = 0L) {
        assertEquals(PopupCommand.Open, popups.toggle(key, at))
        popups.opened(window, key)
    }

    private fun foldAway() = popups.leave(then = false)

    @Test fun aClickOpensAPopup() {
        open(a, "w1")
        assertEquals("w1", popups.open)
        assertEquals(a, popups.openKey)
    }

    @Test fun aClickOnTheOpenPopupsItemFoldsIt() {
        open(a, "w1")
        assertEquals(PopupCommand.Fold, popups.toggle(a, 5L))
    }

    @Test fun aPressOnItsOwnItemFoldsThePopupAndSpendsTheClickThatFollows() {
        open(a, "w1", at = 100L)
        assertEquals(PopupCommand.Fold, popups.press("a", 200L))
        foldAway()
        assertEquals(PopupCommand.Spend, popups.toggle(a, 220L))
    }

    @Test fun aSecondClickTurnsTheFoldingPopupRound() {
        open(a, "w1")
        popups.press("a", 200L)
        foldAway()
        assertEquals(PopupCommand.Spend, popups.toggle(a, 220L))
        assertEquals(PopupCommand.Reopen("w1"), popups.toggle(a, 400L))
        popups.reopened("w1", a)
        assertEquals("w1", popups.open)
        assertEquals(a, popups.openKey)
        assertNull("it is up again, not leaving", foldAway())
    }

    @Test fun aClickOnAPopupStillFoldingWhileAnotherIsOpenOpensItAnew() {
        // The one that is open dissolves first; the fold is only turned round with nothing open.
        open(a, "w1")
        foldAway()
        open(b, "w2")
        assertEquals(PopupCommand.Open, popups.toggle(a, 300L))
    }

    @Test fun theClickAfterThePressIsSpentForUnderASecond() {
        open(a, "w1")
        popups.press("a", 1_000L)
        foldAway()
        assertEquals(PopupCommand.Spend, popups.toggle(a, 1_999L))
    }

    @Test fun aClickASecondAfterThePressIsAClickOfItsOwn() {
        open(a, "w1")
        popups.press("a", 1_000L)
        foldAway()
        assertEquals("it turns the folding popup round", PopupCommand.Reopen("w1"), popups.toggle(a, 2_000L))
    }

    @Test fun aPressOnAnotherItemDissolvesThePopupAndItsClickOpensThatItemsPopup() {
        open(a, "w1")
        assertEquals(PopupCommand.Dissolve, popups.press("b", 200L))
        foldAway()
        assertEquals(PopupCommand.Open, popups.toggle(b, 220L))
    }

    @Test fun aPressAnywhereElseFoldsAndSpendsNothing() {
        open(a, "w1")
        assertEquals(PopupCommand.Fold, popups.press(null, 200L))
        foldAway()
        assertEquals("the next click on it turns it round, it is not eaten", PopupCommand.Reopen("w1"), popups.toggle(a, 220L))
    }

    @Test fun aPressWithNoPopupOpenDoesNothing() {
        assertNull(popups.press("a", 200L))
    }

    @Test fun anotherPopupOpenDissolvesWhileTheNewOneUnfolds() {
        open(a, "w1")
        assertEquals("the controller closes what is open with a dissolve, then opens", PopupCommand.Open, popups.toggle(b, 200L))
    }

    @Test fun clickingAnotherItemForgetsTheSpentClick() {
        open(a, "w1")
        popups.press("a", 200L)
        foldAway()
        assertEquals(PopupCommand.Open, popups.toggle(b, 220L))
        assertEquals("not spent any more: it turns round", PopupCommand.Reopen("w1"), popups.toggle(a, 240L))
    }

    @Test fun aPopupFoldingAwayForAScreenshotDoesNotTurnRound() {
        open(a, "w1")
        popups.leave(then = true)
        assertEquals("the screenshot waits for it to go", PopupCommand.Open, popups.toggle(a, 300L))
    }

    @Test fun aPopupThatCanNoLongerTurnRoundIsLeftToFinishAndANewOneOpens() {
        open(a, "w1")
        foldAway()
        // It said it can't (dissolving, or just gone): the controller opens one as for Open.
        assertEquals(PopupCommand.Reopen("w1"), popups.toggle(a, 220L))
        popups.opened("w2", a)
        assertEquals("w2", popups.open)
        popups.gone("w1")
        assertEquals("w2", popups.open)
    }

    @Test fun onceGoneItOpensAnew() {
        open(a, "w1")
        foldAway()
        popups.gone("w1")
        assertEquals(PopupCommand.Open, popups.toggle(a, 300L))
    }

    @Test fun anItemsPopupAndItsRightClickMenuAreDifferentPopups() {
        val ctx = MenuKey.ItemContext("a")
        open(a, "w1")
        assertEquals(PopupCommand.Open, popups.toggle(ctx, 200L))
    }

    @Test fun onlyOnePopupIsEverFoldingAway() {
        open(a, "w1")
        assertNull(foldAway())
        open(b, "w2")
        assertEquals("the earlier one, to go at once", "w1", foldAway())
    }

    @Test fun anOlderPopupsEndDoesNotForgetTheOneStillLeaving() {
        open(a, "w1")
        foldAway()
        open(b, "w2")
        foldAway()
        popups.gone("w1")
        assertEquals("w2 is still leaving, and can still turn round", PopupCommand.Reopen("w2"), popups.toggle(b, 300L))
    }

    @Test fun takingTheLeavingPopupAwayAtOnceForgetsIt() {
        open(a, "w1")
        foldAway()
        assertEquals("w1", popups.dropLeaving())
        assertEquals(PopupCommand.Open, popups.toggle(a, 300L))
        assertNull(popups.dropLeaving())
    }

    @Test fun whatAKeyBelongsToAndWhatItPrints() {
        assertEquals("chevron", MenuKey.Bar.owner)
        assertEquals("a", MenuKey.Item("a").owner)
        assertEquals("a", MenuKey.ItemContext("a").owner)
        assertEquals("bentobar", MenuKey.Bar.toString())
        assertEquals("item:a", MenuKey.Item("a").toString())
        assertEquals("ctx:a", MenuKey.ItemContext("a").toString())
    }
}
