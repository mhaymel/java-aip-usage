package org.example.fake;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the body the fake backend sends for {@link Scenario#NORMAL}: a usage document,
 * key for key what the real endpoint sends to an account that reports spend, so that a
 * run against the fake backend exercises the same reading of it. Its awkward parts are
 * the real ones: keys beside {@code spend} that are present and null, keys with invented
 * names, and an {@code extra_usage} that restates the spend.
 *
 * <p>The amount used moves a little on each request, so that the row, the history and the
 * differences between readings have something to show rather than one number forever.
 */
final class UsageDocument {

    /** Minor units added per reading: a few cents, as a working account would show. */
    private static final long SPEND_STEP_MINOR = 137;

    private static final long SPEND_START_MINOR = 18_602;

    private static final long SPEND_LIMIT_MINOR = 100_000;

    private final AtomicInteger readings = new AtomicInteger();

    /** How many bodies have been built, which is how far the figures have moved. */
    int readings() {
        return readings.get();
    }

    /**
     * The next reading, a little further on than the last.
     *
     * @param now when it is asked for; nothing in the document is a time, so it
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
