package org.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowDragTest {

    @Test
    void theWindowMovesByAsMuchAsTheMouseDoes() {
        WindowDrag drag = new WindowDrag(500, 300, 420, 280);

        assertEquals(new WindowDrag.Place(420, 280), drag.at(500, 300), "a press with no movement leaves the window where it is");
        assertEquals(new WindowDrag.Place(460, 305), drag.at(540, 325));
        assertEquals(new WindowDrag.Place(-80, 180), drag.at(0, 200), "to the left of where it began, and up");
    }

    @Test
    void thePlaceFollowsFromThePressAndNotFromTheMoveBefore() {
        WindowDrag drag = new WindowDrag(100, 100, 0, 0);

        drag.at(900, 900);

        assertEquals(new WindowDrag.Place(5, -5), drag.at(105, 95), "so that many small moves do not add up their rounding");
    }
}
