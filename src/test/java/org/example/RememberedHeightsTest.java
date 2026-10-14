package org.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RememberedHeightsTest {

    @Test
    void aLargerRememberedHeightIsUsedAndNotStoredAgain() {
        assertEquals(new RememberedHeights.Opening(640, false), RememberedHeights.open(420, 640));
    }

    @Test
    void aRememberedHeightEqualToThePagesOwnIsKept() {
        assertEquals(new RememberedHeights.Opening(420, false), RememberedHeights.open(420, 420));
    }

    @Test
    void aSmallerRememberedHeightIsReplacedByThePagesOwnAndThatIsStored() {
        assertEquals(new RememberedHeights.Opening(420, true), RememberedHeights.open(420, 300));
    }

    @Test
    void withNoRememberedHeightThePagesOwnIsUsedAndStored() {
        assertEquals(new RememberedHeights.Opening(420, true), RememberedHeights.open(420, 0));
    }

    @Test
    void aTallerRowAtTheTimeRaisesTheBarAndReplacesWhatWasStored() {
        // The row had a message line when the panel was opened, so ten times it is more than was remembered.
        assertEquals(new RememberedHeights.Opening(600, true), RememberedHeights.open(600, 420));
    }

    @Test
    void aHeightTheWindowWasGivenIsStoredOnlyIfItIsRealAndNew() {
        assertTrue(RememberedHeights.shouldStore(700, 640));
        assertFalse(RememberedHeights.shouldStore(640, 640), "nothing changed");
        assertFalse(RememberedHeights.shouldStore(0, 640), "not a height");
        assertFalse(RememberedHeights.shouldStore(-20, 640));
    }

    @Test
    void withSeveralViewsTheHeightAboveTheLowestListIsWhatThePageAskedForLessWhatTheListHasAlone() {
        assertEquals(220, RememberedHeights.above(640, 420), "the cost control and what else is not the list's");
        assertEquals(0, RememberedHeights.above(420, 420), "one view: the whole height is the list's");
        assertEquals(0, RememberedHeights.above(420, 0), "the page did not say: the whole height is the list's");
        assertEquals(0, RememberedHeights.above(300, 420), "never less than nothing");
    }

    @Test
    void theLowestListOpensAtItsRememberedHeightBelowTheViewsAboveIt() {
        int above = RememberedHeights.above(640, 420);

        RememberedHeights.Opening taller = RememberedHeights.open(640 - above, 600);
        assertEquals(820, above + taller.height(), "the list at the 600 it was last given, the rest as the page asked");
        assertFalse(taller.store());

        RememberedHeights.Opening first = RememberedHeights.open(640 - above, 0);
        assertEquals(640, above + first.height());
        assertTrue(first.store(), "and what the list has alone is remembered, not the height of the whole window");
    }
}
