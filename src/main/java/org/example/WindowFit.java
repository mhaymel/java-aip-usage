package org.example;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides how big the window should be, from what the page says it needs.
 *
 * <p>The page reports {@code "<width>,<height>"}, optionally followed by {@code ,1} when the window
 * may be resized in height, {@code ,2} when it may be resized in width too, and {@code ,0} when it
 * may not be resized, in CSS pixels, taken from its own
 * content and not from the window, so resizing the window to match never changes
 * the answer. Everything else is defence: a reading that is not that shape is
 * ignored, a size is kept within sane limits, and a size equal to the one already
 * applied is not applied again.
 */
final class WindowFit {

    /**
     * A window size in pixels.
     *
     * @param resize what of the window the person may drag
     */
    record Size(int width, int height, Resize resize) {

        Size(int width, int height) {
            this(width, height, Resize.NONE);
        }

        boolean resizable() {
            return resize != Resize.NONE;
        }
    }

    /** What of the window the person may drag. */
    enum Resize {
        NONE, HEIGHT, BOTH
    }

    static final Size MIN = new Size(160, 32);

    static final Size MAX = new Size(2400, 1600);

    private static final Pattern REPORT = Pattern.compile("(\\d{1,5}),(\\d{1,5})(?:,([012]))?");

    private WindowFit() {
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
        if (width == 0 || height == 0) {
            return Optional.empty();
        }
        return Optional.of(new Size(
                Math.clamp(width, MIN.width(), MAX.width()),
                Math.clamp(height, MIN.height(), MAX.height()),
                match.group(3) == null ? Resize.NONE : Resize.values()[Integer.parseInt(match.group(3))]));
    }
}
