package org.example;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides how big the window should be, from what the page says it needs.
 *
 * <p>The page reports {@code "<width>,<height>"}, optionally followed by {@code ,1} when the window
 * may be resized in height, {@code ,2} when it may be resized in width too, {@code ,0} when it
 * may not be resized (the window with nothing open), {@code ,3} when it may not be resized and is
 * not the window with nothing open either (the settings, which are as tall as their content),
 * {@code ,4} when it may be made wider and its height is the content's (the tab About of the settings), and
 * optionally {@code ,history}, {@code ,log}, {@code ,errors}, {@code ,cost}, {@code ,settings} or {@code ,debug} after that, naming the panel that is open, in CSS pixels, taken from its own
 * content and not from the window, so resizing the window to match never changes
 * the answer. With several views shown at once they are named together, in the order of their icons and joined by
 * {@code +}, as in {@code ,cost+history}, and a number may follow: the height the lowest list of them has when it is
 * shown alone, row included, from which the host works out what a dragged height is for that list. Last, a panel being named,
 * may come {@code ,-<pixels>} (since 0.85): the row is away while the pointer is outside the window, by so many pixels, and every
 * height before it is as if the row were there. Everything else is defence: a reading that is not that shape is
 * ignored, a size is kept within sane limits, and a size equal to the one already
 * applied is not applied again.
 */
final class WindowFit {

    /**
     * A window size in pixels.
     *
     * @param resize what of the window the person may drag
     * @param panel which panel the page has open, if it said ({@code history}, {@code log}, {@code errors}, {@code settings}
     *     {@code cost} or {@code debug}), or several of the views joined by {@code +}; the host remembers the height of the
     *     history and the log, and gives the window its title bar for as long as a panel is open that keeps it
     * @param alone with several views, the height the lowest list has when it is shown alone, row included; 0 when the
     *     page did not say
     * @param away how many pixels the row is away by, while the pointer is outside the window (since 0.85); 0 while it is there.
     *     The heights before it are as if the row were there, so that nothing remembered of a list depends on it.
     */
    record Size(int width, int height, Resize resize, String panel, int alone, int away) {

        Size(int width, int height) {
            this(width, height, Resize.NONE, null, 0, 0);
        }

        Size(int width, int height, Resize resize) {
            this(width, height, resize, null, 0, 0);
        }

        Size(int width, int height, Resize resize, String panel) {
            this(width, height, resize, panel, 0, 0);
        }

        Size(int width, int height, Resize resize, String panel, int alone) {
            this(width, height, resize, panel, alone, 0);
        }

        /** The views that are open, in the order the page named them; one name for a panel alone, none when it named none. */
        List<String> panels() {
            return panel == null ? List.of() : List.of(panel.split("\\+"));
        }

        /** Whether more than one view is shown. */
        boolean several() {
            return panels().size() > 1;
        }

        /** The lowest of the lists that are shown, which takes the height the person drags the window to, or null when no list is. */
        String lowestList() {
            String lowest = null;
            for (String name : panels()) {
                if (name.equals("log") || name.equals("history") || name.equals("errors")) {
                    lowest = name;
                }
            }
            return lowest;
        }

        boolean resizable() {
            return resize == Resize.HEIGHT || resize == Resize.BOTH || resize == Resize.WIDTH;
        }

        /**
         * Whether the person may give the window a width of their own, which is then kept for as long as the same panel
         * stays open with the same flag: a size the page asks for after that changes the height only.
         */
        boolean widthIsThePersons() {
            return resize == Resize.BOTH || resize == Resize.WIDTH;
        }

        /**
         * Whether the window has its title bar for this, whatever the setting says: while the settings are shown, alone or
         * beside views, and the debug settings (since 0.87). No other view has it with the setting off: the lists are made
         * taller and shorter by buttons of their own then, and the window is moved by dragging it. Until 0.86 every view
         * that could be resized had it, the lists among them.
         */
        boolean keepsTitleBar() {
            return panels().stream().anyMatch(name -> name.equals("settings") || name.equals("debug"));
        }
    }

    /**
     * What of the window the person may drag, in the order of the numbers the page says: nothing, the height, both, nothing
     * with a panel open, and the width only, from the width asked for up, the height being the content's.
     */
    enum Resize {
        NONE, HEIGHT, BOTH, FIXED, WIDTH
    }

    // 40 pixels wide at least since 0.96, where it was 160: a row of the status circle and the gear alone is narrower than that, and
    // the window is as wide as the row, with no empty room in it.
    static final Size MIN = new Size(40, 32);

    // No size of a window, only a bound on what the page may report (2,400 by 1,600 until 0.91): since 0.92 the screen the window is
    // on is the largest it is made, which the host applies.
    static final Size MAX = new Size(10000, 10000);

    private static final Pattern REPORT = Pattern.compile("(\\d{1,5}),(\\d{1,5})(?:,([01234])(?:,(debug|(?:settings\\+)?(?:log|history|errors|cost)(?:\\+(?:log|history|errors|cost)){0,3}|settings)(?:,(\\d{1,5}))?(?:,-(\\d{1,4}))?)?)?");

    /** The views in the order of their icons, which is the order the page names them in. */
    private static final List<String> VIEWS = List.of("cost", "history", "log", "errors");

    private WindowFit() {
    }

    /** Whether the views named are each there once and in the order of their icons; a name alone always is. */
    private static boolean inIconOrder(String panel) {
        if (panel == null || !panel.contains("+")) {
            return true;
        }
        int last = -1;
        // The settings, when they are among the views, are named first (since 0.81); the pattern lets them stand nowhere else.
        for (String name : panel.replaceFirst("^settings\\+", "").split("\\+")) {
            int at = VIEWS.indexOf(name);
            if (at <= last) {
                return false;
            }
            last = at;
        }
        return true;
    }

    /**
     * @param reported what the page returned: normally the text {@code "400,42"}
     * @param current the size already applied, if any
     * @return the size to apply now, or empty if the report is unusable or changes nothing
     */
    static Optional<Size> next(Object reported, Optional<Size> current) {
        return parse(reported).filter(size -> !Optional.of(size).equals(current));
    }

    /** The reported size, kept within {@link #MIN} and {@link #MAX}; empty if it is not a size. */
    static Optional<Size> parse(Object reported) {
        if (!(reported instanceof String text)) {
            return Optional.empty();
        }
        Matcher match = REPORT.matcher(text.strip());
        if (!match.matches()) {
            return Optional.empty();
        }
        int width = Integer.parseInt(match.group(1));
        int height = Integer.parseInt(match.group(2));
        if (width == 0 || height == 0 || !inIconOrder(match.group(4))) {
            return Optional.empty();
        }
        return Optional.of(new Size(
                Math.clamp(width, MIN.width(), MAX.width()),
                Math.clamp(height, MIN.height(), MAX.height()),
                match.group(3) == null ? Resize.NONE : Resize.values()[Integer.parseInt(match.group(3))],
                match.group(4),
                match.group(5) == null ? 0 : Math.min(Integer.parseInt(match.group(5)), MAX.height()),
                // The row is never away by more than the height leaves of the window.
                match.group(6) == null ? 0 : Math.min(Integer.parseInt(match.group(6)), Math.clamp(height, MIN.height(), MAX.height()) - MIN.height())));
    }
}
