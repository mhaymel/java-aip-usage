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
}
