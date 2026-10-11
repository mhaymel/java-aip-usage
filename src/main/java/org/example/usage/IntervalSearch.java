package org.example.usage;

/**
 * The search for the shortest time between usage requests that Anthropic accepts, in the manner of a binary search:
 * an interval between a lower and an upper bound, in whole seconds. An answer that is no HTTP 429 says the interval
 * was long enough, so it becomes the upper bound and the next one is half way down to the lower bound; a 429 says it
 * was too short, so it becomes the lower bound and the next one is half way up to the upper bound. It settles a little
 * above the shortest interval that is accepted.
 *
 * <p>It has no clock and no thread: it is told what happened and says what the interval is.
 */
public final class IntervalSearch {

    /** The shortest interval the search ever uses, in seconds. Fixed, and not a setting. */
    public static final int MIN_SECONDS = 10;

    /** The longest, in seconds: five minutes. */
    public static final int MAX_SECONDS = 300;

    /** The interval a search begins with. */
    public static final int START_SECONDS = 60;

    /** How near the upper bound an interval is "at" it, in seconds: a 429 there says the bounds no longer hold. */
    static final int NEAR_SECONDS = 1;

    private int interval;

    private int lower;

    private int upper;

    private IntervalSearch(int interval, int lower, int upper) {
        this.interval = interval;
        this.lower = lower;
        this.upper = upper;
    }

    /** A search from the beginning: 60 seconds, between 10 and 300. */
    public static IntervalSearch fresh() {
        return new IntervalSearch(START_SECONDS, MIN_SECONDS, MAX_SECONDS);
    }

    /**
     * A search that goes on from what was saved. Figures that cannot be a state of the search, a bound outside 10 to
     * 300, a lower bound above the upper one or an interval outside the two, begin it anew.
     */
    public static IntervalSearch of(int interval, int lower, int upper) {
        boolean sound = lower >= MIN_SECONDS && upper <= MAX_SECONDS && lower <= interval && interval <= upper;
        return sound ? new IntervalSearch(interval, lower, upper) : fresh();
    }

    public int interval() {
        return interval;
    }

    public int lower() {
        return lower;
    }

    public int upper() {
        return upper;
    }

    /** Anthropic answered without a 429: the interval was long enough. The next one is half way down to the lower bound. */
    public void succeeded() {
        upper = interval;
        interval = middle(lower, interval);
    }

    /**
     * Anthropic answered HTTP 429: the interval was too short. The next one is half way up to the upper bound. At the
     * upper bound, or within a second of it, there is no way up left: the search has closed in on an interval that no
     * longer holds, so the bounds are set back to 10 and 300 first.
     *
     * @return whether the bounds were set back
     */
    public boolean rateLimited() {
        boolean reset = interval >= upper - NEAR_SECONDS;
        if (reset) {
            lower = MIN_SECONDS;
            upper = MAX_SECONDS;
        } else {
            lower = interval;
        }
        interval = middle(upper, interval);
        return reset;
    }

    /** The middle of two intervals in whole seconds, rounded up, and never outside 10 to 300. */
    private static int middle(int a, int b) {
        return Math.min(MAX_SECONDS, Math.max(MIN_SECONDS, (a + b + 1) / 2));
    }

    /** As the log says it: {@code 35 s (between 10 s and 60 s)}. */
    public String describe() {
        return interval + " s (between " + lower + " s and " + upper + " s)";
    }
}
