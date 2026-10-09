package org.example;

/**
 * The rules for the heights the window is remembered at while the history or the log is open, apart from the
 * window itself so that they can be tested without one.
 *
 * <p>The page asks for a height of its own, ten times the row's as it is then, the <em>internal</em> height. A
 * remembered height is used instead if it is larger. If it is smaller, or there is none, the internal height is used and
 * remembered as the new value, so a height that is too small does not come back.
 */
final class RememberedHeights {

    /**
     * @param height the height of the window's content to open the panel at, in pixels
     * @param store whether this height is to be remembered, replacing what was
     */
    record Opening(int height, boolean store) {
    }

    private RememberedHeights() {
    }

    /**
     * @param internal the height the page asked for
     * @param stored the remembered height, or 0 for none
     */
    static Opening open(int internal, int stored) {
        return stored >= internal ? new Opening(stored, false) : new Opening(internal, true);
    }

    /**
     * Whether a height the person gave the window is to be remembered: it must be a real height and differ from the one
     * already remembered.
     */
    static boolean shouldStore(int dragged, int stored) {
        return dragged > 0 && dragged != stored;
    }
}
