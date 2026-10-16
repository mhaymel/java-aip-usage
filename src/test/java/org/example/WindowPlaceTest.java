package org.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowPlaceTest {

    private static final WindowPlace.Rect SCREEN = new WindowPlace.Rect(0, 0, 1920, 1040);

    @Test
    void inTheUpperLeftTheTopLeftCornerStays() {
        WindowPlace.Rect row = new WindowPlace.Rect(100, 80, 400, 40);

        assertEquals(new WindowPlace.Rect(100, 80, 1200, 600), WindowPlace.anchored(row, 1200, 600, SCREEN));
    }

    @Test
    void inTheLowerLeftTheBottomEdgeStays() {
        WindowPlace.Rect row = new WindowPlace.Rect(10, 990, 400, 40);

        assertEquals(new WindowPlace.Rect(10, 430, 1200, 600), WindowPlace.anchored(row, 1200, 600, SCREEN),
                "the log grows upwards from the taskbar");
    }

    @Test
    void inTheLowerRightTheBottomRightCornerStays() {
        WindowPlace.Rect row = new WindowPlace.Rect(1500, 990, 400, 40);

        assertEquals(new WindowPlace.Rect(700, 430, 1200, 600), WindowPlace.anchored(row, 1200, 600, SCREEN));
    }

    @Test
    void inTheUpperRightTheRightEdgeStays() {
        WindowPlace.Rect row = new WindowPlace.Rect(1500, 10, 400, 40);

        assertEquals(new WindowPlace.Rect(700, 10, 1200, 600), WindowPlace.anchored(row, 1200, 600, SCREEN));
    }

    @Test
    void aWindowOnTheMiddleCountsAsUpperLeft() {
        WindowPlace.Rect row = new WindowPlace.Rect(760, 500, 400, 40);

        assertEquals(new WindowPlace.Rect(760, 500, 400, 300), WindowPlace.anchored(row, 400, 300, SCREEN));
    }

    @Test
    void whatStillDoesNotFitIsMovedTheLeastOntoTheScreen() {
        // In the upper left by its middle, but grown past the bottom and the right of the screen.
        WindowPlace.Rect row = new WindowPlace.Rect(800, 400, 300, 40);

        assertEquals(new WindowPlace.Rect(720, 240, 1200, 800), WindowPlace.anchored(row, 1200, 800, SCREEN));
    }

    @Test
    void aWindowAsLargeAsTheScreenLiesOnIt() {
        WindowPlace.Rect row = new WindowPlace.Rect(1500, 990, 400, 40);

        assertEquals(SCREEN, WindowPlace.anchored(row, 1920, 1040, SCREEN));
    }

    @Test
    void aScreenLeftOfThePrimaryOneHasItsOwnHalves() {
        WindowPlace.Rect left = new WindowPlace.Rect(-1280, 0, 1280, 984);
        WindowPlace.Rect row = new WindowPlace.Rect(-1200, 940, 400, 40);

        assertEquals(new WindowPlace.Rect(-1200, 380, 1000, 600), WindowPlace.anchored(row, 1000, 600, left));
    }

    @Test
    void aWindowOffTheScreenIsMovedBackOntoIt() {
        assertEquals(new WindowPlace.Rect(0, 1000, 400, 40), WindowPlace.onScreen(new WindowPlace.Rect(-50, 1020, 400, 40), SCREEN));
        assertEquals(new WindowPlace.Rect(300, 200, 400, 40), WindowPlace.onScreen(new WindowPlace.Rect(300, 200, 400, 40), SCREEN),
                "one that is on the screen stays");
    }

    @Test
    void shrinkingAtTheBottomKeepsTheBottomEdge() {
        WindowPlace.Rect log = new WindowPlace.Rect(10, 430, 1200, 600);

        assertEquals(new WindowPlace.Rect(10, 990, 400, 40), WindowPlace.anchored(log, 400, 40, SCREEN));
    }
}
