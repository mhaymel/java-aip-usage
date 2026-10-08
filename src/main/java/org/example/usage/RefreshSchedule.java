package org.example.usage;

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

    /** An extra wait imposed after the server asked us to slow down; the longer of this and the interval applies. */
    private long backoffNanos;

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

    /**
     * Asks for the next request to wait at least this long after the last was
     * triggered, whatever the interval. Zero lifts it. A manual request ignores it.
     */
    void setBackoff(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("back-off must not be negative");
        }
        this.backoffNanos = nanos;
    }

    boolean running() {
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
        return Math.max(0, Math.max(intervalNanos, backoffNanos) - elapsed);
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
