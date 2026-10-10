package org.example.usage;

import java.time.Instant;

/**
 * One reading of the usage endpoint: the spend of the account, or none.
 *
 * <p>An account may report no spend at all, so {@link #isEmpty()} is a legitimate
 * answer rather than a failure.
 *
 * <p>{@code fetchedAt} is when this application received the response; the
 * endpoint does not report it.
 */
public record UsageSnapshot(Instant fetchedAt, Spend spend) {

    public boolean isEmpty() {
        return spend == null;
    }
}
