package org.example.usage;

import java.util.OptionalLong;

/**
 * When the next usage request is due. Pure bookkeeping, with no threads and no
 * clock: every method takes the current time as monotonic nanoseconds, so the
 * rules can be tested without waiting. Not thread-safe; {@link UsageService}
 * guards it.
 *
 * <p>The rules: the interval is measured from when the current or most recent
 * request was <em>triggered</em>, whether by the schedule or by a manual
 * refresh. A request is never due while another runs. Changing the interval
 * only moves the due time; it never starts a request of its own. If the new
 * due time has already passed, the request that was coming anyway starts as
 * soon as none is running.
 */
final class RefreshSchedule {

    private long intervalNanos;

    private boolean everTriggered;

    private long lastTriggered;

    private boolean running;

    private boolean requested;

    RefreshSchedule(long intervalNanos) {
        setInterval(intervalNanos);
    }

    long interval() {
        return intervalNanos;
    }

    void setInterval(long nanos) {
        if (nanos <= 0) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.intervalNanos = nanos;
    }

    boolean running() {
        return running;
    }

    boolean isRunning() {
        return running;
    }

    /**
     * Asks for a request now, ahead of schedule.
     *
     * @return {@code false}, changing nothing, if one is already running or
     *     already asked for
     */
    boolean request() {
        if (running || requested) {
            return false;
        }
        requested = true;
        return true;
    }

    /**
     * How long until a request should start: {@code 0} if it is due now,
     * {@link Long#MAX_VALUE} while one is running (the answer is then up to
     * {@link #finish()}).
     */
    long nanosUntilDue(long now) {
        if (running) {
            return Long.MAX_VALUE;
        }
        if (requested || !everTriggered) {
            return 0;
        }
        long elapsed = now - lastTriggered;
        return Math.max(0, intervalNanos - elapsed);
    }

    /**
     * How long until the next scheduled request is due, for showing to a person:
     * the moment the most recent request was triggered plus the interval,
     * less now. Unlike {@link #nanosUntilDue} it does not stop at zero, and it is
     * not given up on while a request runs, so an overdue refresh counts below zero.
     * A manual request that has been asked for and not yet started is due now.
     *
     * @return the time left, negative once overdue; empty before any request has been
     *     triggered, when there is nothing to count to
     */
    OptionalLong nanosUntilNextRefresh(long now) {
        return countdown().nanosUntilNextRefresh(now);
    }

    /**
     * What the countdown is counted from, as it is at this moment: a copy that does not change, so that it can be
     * handed to whoever shows the countdown and read there at any later time without asking the schedule again.
     */
    Countdown countdown() {
        return new Countdown(everTriggered, lastTriggered, intervalNanos, requested);
    }

    /** See {@link #countdown()} and {@link #nanosUntilNextRefresh}. */
    record Countdown(boolean everTriggered, long lastTriggered, long intervalNanos, boolean requested) {

        OptionalLong nanosUntilNextRefresh(long now) {
            if (!everTriggered) {
                return OptionalLong.empty();
            }
            long left = intervalNanos - (now - lastTriggered);
            return OptionalLong.of(requested ? Math.min(0, left) : left);
        }
    }

    /** Records that a request starts now. */
    void begin(long now) {
        if (running) {
            throw new IllegalStateException("a request is already running");
        }
        running = true;
        requested = false;
        everTriggered = true;
        lastTriggered = now;
    }

    void finish() {
        running = false;
    }
}
