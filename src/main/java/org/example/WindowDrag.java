package org.example;

/**
 * A window being moved by the mouse: where the mouse and the window were when the button was pressed, from which
 * follows where the window is for any place the mouse is dragged to. The window keeps under the mouse the point that
 * was under it at the press.
 *
 * @param pressX where the mouse was on the screen when the button was pressed
 * @param pressY likewise, down the screen
 * @param windowX where the window was then
 * @param windowY likewise, down the screen
 */
record WindowDrag(double pressX, double pressY, double windowX, double windowY) {

    /** A place of the window on the screen. */
    record Place(double x, double y) {
    }

    /** Where the window is with the mouse at this place of the screen. */
    Place at(double screenX, double screenY) {
        return new Place(windowX + (screenX - pressX), windowY + (screenY - pressY));
    }
}
