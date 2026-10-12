package org.example.usage;

/**
 * The search for the shortest time between usage requests that Anthropic accepts, in steps of 5 seconds, from 10 to 300:
 * slow down quickly, speed up slowly, with a memory of the refused interval.
 *
 * <p>It remembers one figure, the refused interval: the interval at which the last HTTP 429 came. After a 429 the
 * interval is a tenth longer than that, rounded up to the next 5, at least 5 more. After eight answers in a row without a 429 it is 5
 * seconds shorter, but never as short as the refused one, so it stays just above what was refused and mostly does not run into it
 * again. The memory is forgotten slowly, 5 seconds for every sixty answers without a 429, so that a limit that has gone
 * down is followed down. Before any refusal is known the interval goes down by a tenth with each answer.
 *
 * <p>It has no clock and no thread: it is told what happened and says what the interval is.
 */
public final class IntervalSearch {

    /** The shortest interval the search ever uses, in seconds. Fixed, and not a setting. */
    public static final int MIN_SECONDS = 10;

    /** The longest, in seconds: five minutes. */
    public static final int MAX_SECONDS = 300;

    /** The step of the search, in seconds: every interval it uses is a multiple of this. */
    public static final int STEP_SECONDS = 5;

    /** The interval a search begins with. */
    public static final int START_SECONDS = 60;

    /** How many answers in a row without a 429 it takes to speed up by a step. */
    static final int ANSWERS_PER_STEP = 8;

    /** How many answers in a row without a 429 it takes to forget a step of the refused interval. */
    static final int ANSWERS_PER_DECAY = 60;

    private int interval;

    /** The interval at which the last 429 came, or 0 for none. */
    private int refused;

    private int stepCount;

    private int decayCount;

    private IntervalSearch(int interval, int refused) {
        this.interval = interval;
        this.refused = refused;
    }

    /** A search from the beginning: 60 seconds, and no refusal known. */
    public static IntervalSearch fresh() {
        return new IntervalSearch(START_SECONDS, 0);
    }

    /** The next multiple of 5 that is not less than {@code seconds}. */
    static int roundUp(long seconds) {
        return (int) Math.min(Integer.MAX_VALUE, (seconds + STEP_SECONDS - 1) / STEP_SECONDS * STEP_SECONDS);
    }

    /**
     * A search that goes on from what was saved. Figures that cannot be a state of the search, an interval or a refused
     * interval outside 10 to 300, begin it anew; an interval at or below the refused one is raised to one above it.
     *
     * @param refused the refused interval, or {@code null} for none
     */
    public static IntervalSearch of(int interval, Integer refused) {
        boolean sound = interval >= MIN_SECONDS && interval <= MAX_SECONDS
                && (refused == null || (refused >= MIN_SECONDS && refused <= MAX_SECONDS));
        if (!sound) {
            return fresh();
        }
        // Figures of a version that searched in single seconds are rounded up to the steps.
        int memory = refused == null ? 0 : roundUp(refused);
        int step = roundUp(interval);
        return new IntervalSearch(memory > 0 ? Math.min(MAX_SECONDS, Math.max(step, memory + STEP_SECONDS)) : step, memory);
    }

    public int interval() {
        return interval;
    }

    /** The interval at which the last refusal came, or {@code null} for none. */
    public Integer refused() {
        return refused == 0 ? null : refused;
    }

    /** Anthropic answered without a 429. */
    public void succeeded() {
        if (refused == 0) {
            // A tenth less, rounded up to the next 5, and never less than 5 below where it was.
            interval = Math.max(MIN_SECONDS, Math.min(roundUp((interval * 9L + 9) / 10), interval - STEP_SECONDS));
            return;
        }
        if (++stepCount >= ANSWERS_PER_STEP) {
            stepCount = 0;
            interval = Math.min(interval, Math.max(Math.max(MIN_SECONDS, refused + STEP_SECONDS), interval - STEP_SECONDS));
        }
        if (++decayCount >= ANSWERS_PER_DECAY) {
            decayCount = 0;
            refused = refused - STEP_SECONDS < MIN_SECONDS ? 0 : refused - STEP_SECONDS;
        }
    }

    /** Anthropic answered HTTP 429. */
    public void rateLimited() {
        rateLimited(0);
    }

    /**
     * A 429 with which the server named a wait, in seconds; none is 0. The interval that was refused is remembered and the
     * new one is a tenth longer; when the wait the server names is longer than that, the interval becomes that wait, so that
     * there is one figure for the wait. Never more than 300 seconds.
     */
    public void rateLimited(long serverSeconds) {
        refused = interval;
        stepCount = 0;
        decayCount = 0;
        interval = Math.min(MAX_SECONDS, Math.max(roundUp((refused * 11L + 9) / 10), refused + STEP_SECONDS));
        if (serverSeconds > interval) {
            interval = Math.min(MAX_SECONDS, roundUp(serverSeconds));
        }
    }

    /** As the log says it: {@code 35 s (refused at 31 s)}, or {@code 35 s (no refusal yet)}. */
    public String describe() {
        return interval + " s (" + (refused == 0 ? "no refusal yet" : "refused at " + refused + " s") + ")";
    }
}
