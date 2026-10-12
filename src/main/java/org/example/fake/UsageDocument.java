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

    private final AtomicInteger readings = new AtomicInteger();

    /** Draws the cents the amount used grows by at an answer: a whole number from 0 to the most there may be. */
    private final java.util.function.IntUnaryOperator dice;

    private FakeOptions options;

    /** What the account has spent, in cents: it begins at the starting amount and grows at each answer. */
    private long usedMinor;

    UsageDocument() {
        this(FakeOptions.DEFAULTS, most -> most);
    }

    /** @param dice given the most the amount may grow by, in cents, says by how much it does: a whole number from 0 to that */
    UsageDocument(FakeOptions options, java.util.function.IntUnaryOperator dice) {
        this.options = options;
        this.dice = dice;
        this.usedMinor = options.startCents();
    }

    int readings() {
        return readings.get();
    }

    /**
     * Takes other options while the backend runs. The amount used goes on from where it is, unless the starting amount
     * is another one: then it begins there again.
     */
    synchronized void configure(FakeOptions wanted) {
        if (wanted.startCents() != options.startCents()) {
            usedMinor = wanted.startCents();
        }
        options = wanted;
    }

    synchronized String next(Instant now) {
        readings.incrementAndGet();
        usedMinor += Math.max(0, Math.min(options.increaseCents(), dice.applyAsInt(options.increaseCents())));
        long limitMinor = options.budgetCents();
        int percent = (int) (usedMinor * 100 / limitMinor);

        // Written out rather than assembled member by member: the point is to send exactly
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
                limitMinor,
                usedMinor,
                usedMinor * 100.0 / limitMinor,
                usedMinor,
                limitMinor,
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

    /**
     * What Anthropic answers for a seat-based account, cut down to what the program looks at: the limits of a session and
     * of a week, and a spend that is there and not enabled.
     */
    static String seatBased() {
        return """
                {"five_hour": {"utilization": 4.0, "resets_at": "2026-10-11T13:40:00+00:00"},
                 "seven_day": {"utilization": 4.0, "resets_at": "2026-10-16T09:00:00+00:00"},
                 "limits": [{"kind": "session", "group": "session", "percent": 4, "severity": "normal"},
                            {"kind": "weekly_all", "group": "weekly", "percent": 4, "severity": "normal"}],
                 "spend": {"used": {"amount_minor": 0, "currency": "USD", "exponent": 2}, "limit": null, "percent": 0,
                           "severity": "normal", "enabled": false, "disabled_reason": null}}
                """;
    }

    /** An error body of the kind a real endpoint sends with a 4xx, which is not a usage document. */
    static String error(String type) {
        return String.format(
                Locale.ROOT, "{\"type\": \"error\", \"error\": {\"type\": \"%s\", \"message\": \"fake backend\"}}", type);
    }
}
