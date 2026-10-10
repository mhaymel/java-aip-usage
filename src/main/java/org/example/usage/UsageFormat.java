package org.example.usage;

import java.util.Locale;
import java.util.Optional;

/**
 * The two formats the usage endpoint answers in. Which one an account gets depends on
 * its subscription and on nothing else; the program tells them apart from each response.
 *
 * <p>The names are this project's, after Anthropic's own words for the two kinds of plan.
 */
public enum UsageFormat {

    /** An account on a usage-based plan: what it has spent, against a spend limit. */
    USAGE_BASED("usage-based"),

    /** An account on a plan with an allowance: a five-hour session limit and a weekly limit. */
    SEAT_BASED("seat-based");

    private final String text;

    UsageFormat(String text) {
        this.text = text;
    }

    /** The name as the log, the command line and the API write it. */
    public String text() {
        return text;
    }

    /** The format of that name, read without regard to case and surrounding blanks. */
    public static Optional<UsageFormat> ofText(String name) {
        String wanted = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        for (UsageFormat format : values()) {
            if (format.text.equals(wanted)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** The names, in order, separated by a comma and a space. */
    public static String names() {
        return USAGE_BASED.text + ", " + SEAT_BASED.text;
    }
}
