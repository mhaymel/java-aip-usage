package org.example;

/**
 * Where the window goes when the program changes its size (since 0.93): towards the room there is on the screen, so that a
 * window low on the screen grows upwards and one on the right grows to the left, and never partly off the screen. A window
 * that gets smaller keeps its top-left corner (since 0.100).
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
     * The window of the new size where the program has made it smaller (since 0.100): width and height each on its own, a side
     * that gets smaller keeps the window's left edge, or its top edge, so that its top-left corner stays; a side that gets larger
     * grows towards the room there is, as {@link #anchored} has it. Then moved onto the screen, see {@link #onScreen}. A window
     * that grows on both sides is placed as {@link #anchored} places it.
     */
    static Rect shrunk(Rect before, double width, double height, Rect screen) {
        Rect grown = anchored(before, width, height, screen);
        double x = width < before.width() ? before.x() : grown.x();
        double y = height < before.height() ? before.y() : grown.y();
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
