package org.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointerAwayTest {

    @Test
    void thePointerIsAwayOnceItHasBeenOutsideForAMoment() {
        PointerAway pointer = new PointerAway();
        assertFalse(pointer.away(true, 0));
        assertFalse(pointer.away(false, 1000), "just left");
        assertFalse(pointer.away(false, 1000 + PointerAway.DELAY_MILLIS - 1));
        assertTrue(pointer.away(false, 1000 + PointerAway.DELAY_MILLIS));
        assertTrue(pointer.away(false, 9000), "and stays away");
    }

    @Test
    void thePointerIsBackTheMomentItIsInside() {
        PointerAway pointer = new PointerAway();
        pointer.away(false, 0);
        assertTrue(pointer.away(false, 600));
        assertFalse(pointer.away(true, 650));
        assertFalse(pointer.away(false, 700), "outside again: the moment begins anew");
        assertTrue(pointer.away(false, 1200));
    }

    @Test
    void aPointerThatBrushesPastTheEdgeMovesNothing() {
        PointerAway pointer = new PointerAway();
        assertFalse(pointer.away(false, 0));
        assertFalse(pointer.away(true, 300));
        assertFalse(pointer.away(false, 400));
        assertFalse(pointer.away(false, 800), "outside since 400 only");
    }

    @Test
    void theWindowIsTheWholeOfItsBounds() {
        assertTrue(PointerAway.inside(100, 200, 100, 200, 400, 300), "the left and the top edge are inside");
        assertTrue(PointerAway.inside(499.5, 499.5, 100, 200, 400, 300));
        assertFalse(PointerAway.inside(500, 300, 100, 200, 400, 300), "the right edge is outside");
        assertFalse(PointerAway.inside(300, 500, 100, 200, 400, 300), "and the bottom one");
        assertFalse(PointerAway.inside(99, 300, 100, 200, 400, 300));
        assertFalse(PointerAway.inside(300, 199, 100, 200, 400, 300));
    }
}
