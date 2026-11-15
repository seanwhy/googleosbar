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

    // ---- a popup that stays (a mirror, a prompter): a press elsewhere gives the keys back and closes nothing ----

    @Test fun aPressElsewhereLeavesAPopupThatStaysOpenAndGivesTheKeysBack() {
        open(a, "w1")
        assertEquals(PopupCommand.LetKeysGo, popups.press(null, 200L, stays = true))
        assertEquals("still up", "w1", popups.open)
        assertEquals("and again for the next press elsewhere", PopupCommand.LetKeysGo, popups.press(null, 300L, stays = true))
        assertEquals(PopupCommand.Fold, popups.toggle(a, 400L))
    }

    @Test fun aPressInsideAPopupThatGaveTheKeysBackTakesThemAgain() {
        open(a, "w1")
        assertNull("it has them: nothing to take", popups.pressInside())
        popups.press(null, 200L, stays = true)
        assertEquals(PopupCommand.TakeKeys, popups.pressInside())
        assertNull("taken once", popups.pressInside())
    }

    @Test fun aPopupThatStaysStillFoldsForItsItemAndGivesWayToAnother() {
        open(a, "w1")
        assertEquals("a press on its own item", PopupCommand.Fold, popups.press("a", 200L, stays = true))
        foldAway()
        assertEquals("and that press's click is spent", PopupCommand.Spend, popups.toggle(a, 220L))
        popups.gone("w1")
        open(a, "w2", at = 2_000L)
        assertEquals("a press on another item", PopupCommand.Dissolve, popups.press("b", 2_200L, stays = true))
    }

    @Test fun aNewPopupHasTheKeys() {
        open(a, "w1")
        popups.press(null, 200L, stays = true)
        foldAway()
        open(b, "w2")
        assertNull(popups.pressInside())
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

    /**
     * The ‹ menu has one key in every area. Still folding away under one strip, it is taken away at once before a click
     * on another strip's ‹ is asked about: that click opens it anew, where it was pressed.
     */
    @Test fun aPopupTakenAwayWhileItLeavesIsOpenedAnewAndNeverTurnedRound() {
        open(MenuKey.Bar, "w1")
        foldAway()
        assertEquals("left to fold, its own click turns it round where it stands", PopupCommand.Reopen("w1"), popups.toggle(MenuKey.Bar, 100L))
        assertEquals("w1", popups.dropLeaving())
        assertEquals(PopupCommand.Open, popups.toggle(MenuKey.Bar, 100L))
        assertEquals(PopupCommand.Open, popups.toggle(b, 100L))
        assertNull("nothing is left to take away", popups.dropLeaving())
    }

    /**
     * Only its own key could turn a leaving popup round, so only for that key is it taken away: one leaving for another
     * key goes on leaving while the new one unfolds, as it does within one strip.
     */
    @Test fun aLeavingPopupIsTakenAwayOnlyForItsOwnKey() {
        open(a, "w1")
        foldAway()
        assertNull("another key's click leaves it leaving", popups.dropLeaving(b))
        assertEquals("and it can still turn round", PopupCommand.Reopen("w1"), popups.toggle(a, 100L))
        assertEquals("w1", popups.dropLeaving(a))
        assertEquals(PopupCommand.Open, popups.toggle(a, 100L))
        assertNull("nothing is left to take away", popups.dropLeaving(a))
    }

    @Test fun withNothingLeavingNoKeyTakesAnythingAway() {
        assertNull(popups.dropLeaving(a))
        open(a, "w1")
        assertNull("an open popup is not a leaving one", popups.dropLeaving(a))
        assertEquals("w1", popups.open)
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
