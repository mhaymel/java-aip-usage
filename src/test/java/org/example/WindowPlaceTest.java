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
    void shrinkingAtTheBottomKeepsTheBottomEdgeWhenAnchored() {
        WindowPlace.Rect log = new WindowPlace.Rect(10, 430, 1200, 600);

        assertEquals(new WindowPlace.Rect(10, 990, 400, 40), WindowPlace.anchored(log, 400, 40, SCREEN),
                "anchored alone, as until 0.99; the program shrinks by shrunk since 0.100");
    }

    @Test
    void shrinkingInTheLowerRightKeepsTheTopLeftCorner() {
        // The history closed in the lower right: the row stays where the top of the view was (since 0.100).
        WindowPlace.Rect history = new WindowPlace.Rect(700, 430, 1200, 600);

        assertEquals(new WindowPlace.Rect(700, 430, 400, 40), WindowPlace.shrunk(history, 400, 40, SCREEN));
    }

    @Test
    void growingInTheLowerRightStillGrowsUpAndLeft() {
        WindowPlace.Rect row = new WindowPlace.Rect(1500, 990, 400, 40);

        assertEquals(new WindowPlace.Rect(700, 430, 1200, 600), WindowPlace.shrunk(row, 1200, 600, SCREEN),
                "a window that grows on both sides is placed as anchored places it");
    }

    @Test
    void oneSideShrinkingAndTheOtherGrowingAreTakenEachOnItsOwn() {
        // In the lower right: narrower keeps the left edge, taller grows upwards from the bottom edge.
        WindowPlace.Rect wide = new WindowPlace.Rect(700, 630, 1200, 400);
        assertEquals(new WindowPlace.Rect(700, 430, 400, 600), WindowPlace.shrunk(wide, 400, 600, SCREEN));

        // Wider grows to the left from the right edge, lower keeps the top edge.
        WindowPlace.Rect tall = new WindowPlace.Rect(1500, 430, 400, 600);
        assertEquals(new WindowPlace.Rect(700, 430, 1200, 40), WindowPlace.shrunk(tall, 1200, 40, SCREEN));
    }

    @Test
    void whatWouldLieOffTheScreenAfterShrinkingIsMovedOntoIt() {
        // A window the person had dragged partly off the bottom right of the screen.
        WindowPlace.Rect off = new WindowPlace.Rect(1800, 1000, 600, 400);

        assertEquals(new WindowPlace.Rect(1520, 1000, 400, 40), WindowPlace.shrunk(off, 400, 40, SCREEN));
    }
}
