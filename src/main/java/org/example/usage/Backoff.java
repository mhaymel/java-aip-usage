package org.example.usage;

/**
 * How long to hold back, beyond the usual interval, because the server said to
 * slow down. Pure arithmetic on nanoseconds, with no threads and no clock.
 *
 * <p>A 429 doubles the hold: the first makes it twice the interval, each further
 * one doubles it again, up to a maximum, or the server's own {@code Retry-After}
 * if that is longer. A success does not lift it at once. It eases it, taking an
 * eighth off, and the eased value is the new wait. Lifting it entirely would put
 * the next request back at the pace that was just refused, and the endpoint would
 * answer 429 again; easing finds the pace it will accept and stays near it.
 * Once the hold is no longer than the interval, it is gone.
 *
 * <p>Nothing else touches it: a failure that is not a 429 says nothing about the
 * rate limit.
 */
final class Backoff {

    /** A success leaves this fraction of the hold: 7/8, so an eighth comes off. */
    private static final long EASE_NUMERATOR = 7;

    private static final long EASE_DENOMINATOR = 8;

    private final long maxNanos;

    private long holdNanos;

    /** @param maxNanos the longest hold that doubling alone may reach */
    Backoff(long maxNanos) {
        if (maxNanos <= 0) {
            throw new IllegalArgumentException("the maximum must be positive");
        }
        this.maxNanos = maxNanos;
    }

    /** The hold in force, or zero for none. The wait is the longer of this and the interval. */
    long hold() {
        return holdNanos;
    }

    /**
     * The server answered 429.
     *
     * @param intervalNanos the usual interval
     * @param retryAfterNanos the wait the server asked for, or zero if it did not say
     * @return the hold now in force
     */
    long rateLimited(long intervalNanos, long retryAfterNanos) {
        long doubled = Math.max(holdNanos, intervalNanos) * 2;
        holdNanos = Math.max(Math.min(doubled, maxNanos), retryAfterNanos);
        return holdNanos;
    }

    /**
     * A request succeeded: ease the hold by an eighth, or drop it if that leaves it
     * no longer than the interval.
     *
     * @return the hold now in force
     */
    long succeeded(long intervalNanos) {
        if (holdNanos == 0) {
            return 0;
        }
        holdNanos = holdNanos / EASE_DENOMINATOR * EASE_NUMERATOR;
        if (holdNanos <= intervalNanos) {
            holdNanos = 0;
        }
        return holdNanos;
    }
}
