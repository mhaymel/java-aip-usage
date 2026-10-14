package org.example;

/**
 * Whether the pointer has been outside the window long enough for the row to go (since 0.85), and back in it. The host asks
 * on every tick of its fitting, with where the pointer is; the page decides whether the row may go at all.
 *
 * <p>The pointer is away once it has been outside the window for {@link #DELAY_MILLIS} without a break, so that a pointer
 * that only brushes past the edge moves nothing; it is back the moment it is inside again. The window is the whole of it,
 * its title bar and frame included: on them the pointer is still in the program.
 */
final class PointerAway {

    /** How long the pointer is outside the window before it counts as away. */
    static final long DELAY_MILLIS = 500;

    /** When the pointer was first seen outside without a break, or -1 while it is inside. */
    private long outsideSince = -1;

    /**
     * @param inside whether the pointer is inside the window now
     * @param nowMillis the time now, on any clock that only goes forward
     * @return whether the pointer counts as away
     */
    boolean away(boolean inside, long nowMillis) {
        if (inside) {
            outsideSince = -1;
            return false;
        }
        if (outsideSince < 0) {
            outsideSince = nowMillis;
        }
        return nowMillis - outsideSince >= DELAY_MILLIS;
    }

    /** Whether a point is inside a window at {@code x, y} of that size, its edges at the left and at the top included. */
    static boolean inside(double pointX, double pointY, double x, double y, double width, double height) {
        return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
    }
}
