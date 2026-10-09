package org.example.fake;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the body the fake backend sends for {@link Scenario#NORMAL}: a usage document
 * in the shape the real endpoint sends, carrying the same awkward parts, so that a run
 * against the fake backend exercises the same reading of it. Those parts are a window at
 * exactly zero, a window whose {@code resets_at} is null, keys that are null, an
 * {@code extra_usage} that also holds a {@code utilization} and must stay out of the
 * window list, and keys that are no window at all.
 *
 * <p>The figures move a little on each request, so that the row, the history and the
 * differences between readings have something to show rather than one number forever.
 */
final class UsageDocument {

    /** How far the five-hour window climbs per reading, in percentage points. */
    private static final double FIVE_HOUR_STEP = 1.7;

    private static final double SEVEN_DAY_STEP = 0.35;

    /** Minor units added per reading: a few cents, as a working account would show. */
    private static final long SPEND_STEP_MINOR = 137;

    private static final long SPEND_START_MINOR = 18_602;

    private static final long SPEND_LIMIT_MINOR = 100_000;

    private final AtomicInteger readings = new AtomicInteger();

    /** How many bodies have been built, which is how far the figures have moved. */
    int readings() {
        return readings.get();
    }

    /** The next reading, a little further on than the last. */
    String next(Instant now) {
        int reading = readings.incrementAndGet();
        double fiveHour = climb(8.0, FIVE_HOUR_STEP, reading);
        double sevenDay = climb(41.0, SEVEN_DAY_STEP, reading);
        long usedMinor = SPEND_START_MINOR + reading * SPEND_STEP_MINOR;
        int percent = (int) (usedMinor * 100 / SPEND_LIMIT_MINOR);

        // Written out rather than assembled with Jackson: the point is to send exactly
        // what the endpoint sends, oddities and all, which a serialised model would tidy.
        // Locale.ROOT throughout, or a comma would appear where JSON needs a point.
        return String.format(Locale.ROOT, """
                {
                  "five_hour": {"utilization": %.2f, "resets_at": "%s"},
                  "seven_day": {"utilization": %.2f, "resets_at": "%s"},
                  "seven_day_opus": {"utilization": 0, "resets_at": "%s"},
                  "seven_day_sonnet": null,
                  "juniper_tide": null,
                  "cedar_ember": {"utilization": %.2f, "resets_at": null},
                  "extra_usage": {"utilization": %.2f, "is_enabled": true},
                  "limits": [],
                  "spend": {
                    "used": {"amount_minor": %d, "currency": "USD", "exponent": 2},
                    "limit": {"amount_minor": %d, "currency": "USD", "exponent": 2},
                    "percent": %d,
                    "severity": "%s",
                    "enabled": true,
                    "disabled_reason": null
                  },
                  "member_dashboard_available": false
                }
                """,
                fiveHour, now.plus(Duration.ofHours(5)),
                sevenDay, now.plus(Duration.ofDays(7)),
                now.plus(Duration.ofDays(7)),
                climb(3.5, 0.1, reading),
                climb(5.5, 0.2, reading),
                usedMinor,
                SPEND_LIMIT_MINOR,
                percent,
                percent >= 80 ? "warning" : "normal");
    }

    /** A figure that rises with each reading and then stays just short of full. */
    private static double climb(double from, double step, int reading) {
        return Math.min(99.9, from + step * reading);
    }

    /**
     * A usage document with text after it, which a strict reader must refuse rather
     * than stopping at the end of the object.
     */
    String withTrailingText(Instant now) {
        return next(now) + System.lineSeparator() + "and then some text that has no business here";
    }

    /** A JSON object that is some other document: no {@code spend}, and nothing shaped like a window. */
    static String neitherSpendNorWindows() {
        return """
                {"detail": "this is not a usage document", "limits": [], "member_dashboard_available": false}
                """;
    }

    /** An error body of the kind a real endpoint sends with a 4xx, which is not a usage document. */
    static String error(String type) {
        return String.format(
                Locale.ROOT, "{\"type\": \"error\", \"error\": {\"type\": \"%s\", \"message\": \"fake backend\"}}", type);
    }
}
