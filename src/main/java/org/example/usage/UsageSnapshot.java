package org.example.usage;

import java.time.Instant;

/**
 * One reading of the usage endpoint, in one of its two formats: the spend of an account on
 * a usage-based plan, or the two limits of an account on a seat-based plan. Never both: a
 * response that carries plan windows is seat-based, and its spend is ignored.
 *
 * <p>An account may report nothing at all, so {@link #isEmpty()} is a legitimate answer
 * rather than a failure, and such a reading has no format.
 *
 * <p>{@code fetchedAt} is when this application received the response; the
 * endpoint does not report it.
 */
public record UsageSnapshot(Instant fetchedAt, Spend spend, PlanLimits limits) {

    /** A reading in the usage-based format, or one with nothing to report when there is no spend. */
    public UsageSnapshot(Instant fetchedAt, Spend spend) {
        this(fetchedAt, spend, null);
    }

    /** A reading in the seat-based format. */
    public static UsageSnapshot seatBased(Instant fetchedAt, PlanLimits limits) {
        return new UsageSnapshot(fetchedAt, null, limits);
    }

    public boolean isEmpty() {
        return spend == null && limits == null;
    }

    /** The format of this reading, or {@code null} for one that reports nothing and so has none. */
    public UsageFormat format() {
        return limits != null ? UsageFormat.SEAT_BASED : spend != null ? UsageFormat.USAGE_BASED : null;
    }
}
