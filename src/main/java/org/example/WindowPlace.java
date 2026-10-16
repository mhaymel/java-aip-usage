package org.example;

/**
 * Where the window goes when the program changes its size (since 0.93): towards the room there is on the screen, so that a
 * window low on the screen grows upwards and one on the right grows to the left, and never partly off the screen.
 */
final class WindowPlace {

    /** A rectangle on the screen: a window's place and size, or the visible area of a screen. */
    record Rect(double x, double y, double width, double height) {

        double maxX() {
            return x + width;
        }

        double maxY() {
            return y + height;
        }

        double middleX() {
            return x + width / 2;
        }

        double middleY() {
            return y + height / 2;
        }
    }

    private WindowPlace() {
    }

    /**
     * The window of the new size, its corner nearest the screen's corner where it was: in the lower half of the screen its
     * bottom edge stays, in the right half its right edge; in the upper left its top left corner, as a window grows by itself.
     * The halves are decided by the middle of the window as it was. Then moved onto the screen, see {@link #onScreen}.
     */
    static Rect anchored(Rect before, double width, double height, Rect screen) {
        double x = before.middleX() > screen.middleX() ? before.maxX() - width : before.x();
        double y = before.middleY() > screen.middleY() ? before.maxY() - height : before.y();
        return onScreen(new Rect(x, y, width, height), screen);
    }

    /**
     * The window moved the least that puts it wholly on the screen, across and down each on its own. One larger than the
     * screen, which the program never makes it, lies at the screen's left or top edge.
     */
    static Rect onScreen(Rect window, Rect screen) {
        return new Rect(within(window.x(), window.width(), screen.x(), screen.maxX()),
                within(window.y(), window.height(), screen.y(), screen.maxY()), window.width(), window.height());
    }

    private static double within(double at, double size, double least, double most) {
        return Math.max(least, Math.min(at, most - size));
    }
}
