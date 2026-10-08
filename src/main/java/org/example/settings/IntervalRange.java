package org.example.settings;

/** The accepted values of one interval setting, in whole seconds. */
public record IntervalRange(String name, int min, int max, int defaultValue) {

    /**
     * How often the backend fetches usage from Anthropic. The default is a minute:
     * the endpoint seems to accept about one request a minute, and answers 429 to a
     * faster pace sustained for long.
     */
    public static final IntervalRange USAGE = new IntervalRange("usage interval", 5, 3600, 60);

    /** How often the UI asks the backend for the latest state. Set by command line only. */
    public static final IntervalRange POLL = new IntervalRange("poll interval", 1, 60, 1);

    public boolean contains(long seconds) {
        return seconds >= min && seconds <= max;
    }

    /** The reason {@code seconds} is refused, in words fit for a user. */
    public String describeLimit() {
        return "The " + name + " must be a whole number of seconds from " + min + " to " + max + ".";
    }
}
