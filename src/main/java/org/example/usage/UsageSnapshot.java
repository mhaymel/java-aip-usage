package org.example.usage;

import java.time.Instant;

/**
 * One reading of the usage endpoint, in the usage-based format, which is the one
 * format this application supports: the spend of the account, or none.
 *
 * <p>An account may report no spend at all, so {@link #isEmpty()} is a legitimate
 * answer rather than a failure. A response in the seat-based format, the one that
 * carries plan windows, never becomes a reading: the parser refuses it.
 *
 * <p>{@code fetchedAt} is when this application received the response; the
 * endpoint does not report it.
 */
public record UsageSnapshot(Instant fetchedAt, Spend spend) {

    public boolean isEmpty() {
        return spend == null;
    }
}
