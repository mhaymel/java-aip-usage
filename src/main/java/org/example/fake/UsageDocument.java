package org.example.fake;

import org.example.usage.UsageFormat;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the body the fake backend sends for {@link Scenario#NORMAL}: a usage document
 * in the usage-based format, key for key what the real endpoint sends to an account on a
 * usage-based plan, so that a run against the fake backend exercises the same reading of
 * it. Its awkward parts are the real ones: the keys of the plan windows present and null,
 * keys with invented names, an {@code extra_usage} that restates the spend and holds a
 * {@code utilization} without being a window, and keys that are no window at all.
 *
 * <p>It can build the same in the seat-based format, the one that carries plan windows,
 * for a run that is to show that format or a change from one to the other.
 *
 * <p>The amount used moves a little on each request, so that the row, the history and the
 * differences between readings have something to show rather than one number forever.
 */
final class UsageDocument {

    /** Minor units added per reading: a few cents, as a working account would show. */
    private static final long SPEND_STEP_MINOR = 137;

    private static final long SPEND_START_MINOR = 18_602;

    private static final long SPEND_LIMIT_MINOR = 100_000;

    /** How far the five-hour limit climbs with each reading, in percentage points, and where it begins after it was set back. */
    private static final double FIVE_HOUR_START = 8.0;

    private static final double FIVE_HOUR_STEP = 1.7;

    private static final double SEVEN_DAY_START = 41.0;

    private static final double SEVEN_DAY_STEP = 0.35;

    private static final Duration FIVE_HOURS = Duration.ofHours(5);

    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    private final AtomicInteger readings = new AtomicInteger();

    /** The seat-based figures: when each limit is next set back, and how many readings it has climbed since it last was. */
    private Instant fiveHourResets;

    private int fiveHourReadings;

    private Instant sevenDayResets;

    private int sevenDayReadings;

    /** How many bodies have been built, which is how far the figures have moved. */
    int readings() {
        return readings.get();
    }

    /**
     * The next reading, a little further on than the last.
     *
     * @param now when it is asked for; nothing in the usage-based format is a time, so it
     *            is not written anywhere, and is taken so that the callers need not change
     *            the day the document carries one
     */
    String next(Instant now) {
        int reading = readings.incrementAndGet();
        long usedMinor = SPEND_START_MINOR + reading * SPEND_STEP_MINOR;
        int percent = (int) (usedMinor * 100 / SPEND_LIMIT_MINOR);

        // Written out rather than assembled with Jackson: the point is to send exactly
        // what the endpoint sends, oddities and all, which a serialised model would tidy.
        // Locale.ROOT throughout, or a comma would appear where JSON needs a point.
        return String.format(Locale.ROOT, """
                {
                  "five_hour": null,
                  "seven_day": null,
                  "seven_day_oauth_apps": null,
                  "seven_day_opus": null,
                  "seven_day_sonnet": null,
                  "juniper_tide": null,
                  "cedar_ember": null,
                  "extra_usage": {
                    "is_enabled": true,
                    "monthly_limit": %d,
                    "used_credits": %d.0,
                    "utilization": %.3f,
                    "currency": "USD",
                    "decimal_places": 2
                  },
                  "limits": [],
                  "spend": {
                    "used": {"amount_minor": %d, "currency": "USD", "exponent": 2},
                    "limit": {"amount_minor": %d, "currency": "USD", "exponent": 2},
                    "percent": %d,
                    "severity": "%s",
                    "enabled": true,
                    "disabled_reason": null
                  },
                  "member_dashboard_available": true,
                  "seven_day_breakdown": null
                }
                """,
                SPEND_LIMIT_MINOR,
                usedMinor,
                usedMinor * 100.0 / SPEND_LIMIT_MINOR,
                usedMinor,
                SPEND_LIMIT_MINOR,
                percent,
                percent >= 80 ? "warning" : "normal");
    }

    /** The next reading in that format. */
    String next(Instant now, UsageFormat format) {
        return format == UsageFormat.SEAT_BASED ? nextSeatBased(now) : next(now);
    }

    /**
     * The next reading in the seat-based format, key for key what an account on a seat-based plan is sent: the five-hour
     * session limit and the weekly limit, each with a utilization that climbs a little with every reading and a reset time
     * that is a fixed time ahead, and is set back when it is reached; a further window, which must be passed over; and a
     * spend that is not enabled.
     */
    synchronized String nextSeatBased(Instant now) {
        readings.incrementAndGet();
        if (fiveHourResets == null || !now.isBefore(fiveHourResets)) {
            fiveHourResets = now.plus(FIVE_HOURS);
            fiveHourReadings = 0;
        }
        if (sevenDayResets == null || !now.isBefore(sevenDayResets)) {
            sevenDayResets = now.plus(SEVEN_DAYS);
            sevenDayReadings = 0;
        }
        fiveHourReadings++;
        sevenDayReadings++;
        return String.format(Locale.ROOT, """
                {
                  "five_hour": {"utilization": %.2f, "resets_at": "%s"},
                  "seven_day": {"utilization": %.2f, "resets_at": "%s"},
                  "seven_day_oauth_apps": null,
                  "seven_day_opus": {"utilization": 0, "resets_at": null},
                  "seven_day_sonnet": null,
                  "juniper_tide": null,
                  "cedar_ember": null,
                  "extra_usage": {
                    "is_enabled": false,
                    "monthly_limit": null,
                    "used_credits": null,
                    "utilization": null
                  },
                  "limits": [],
                  "spend": {
                    "used": null,
                    "limit": null,
                    "percent": null,
                    "severity": null,
                    "enabled": false,
                    "disabled_reason": "not_applicable"
                  },
                  "member_dashboard_available": false,
                  "seven_day_breakdown": null
                }
                """,
                climb(FIVE_HOUR_START, FIVE_HOUR_STEP, fiveHourReadings), fiveHourResets,
                climb(SEVEN_DAY_START, SEVEN_DAY_STEP, sevenDayReadings), sevenDayResets);
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
        return withTrailingText(now, UsageFormat.USAGE_BASED);
    }

    String withTrailingText(Instant now, UsageFormat format) {
        return next(now, format) + System.lineSeparator() + "and then some text that has no business here";
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
