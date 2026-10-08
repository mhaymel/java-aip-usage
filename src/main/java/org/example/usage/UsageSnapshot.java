package org.example.usage;

import java.time.Instant;
import java.util.List;

/**
 * One reading of the usage endpoint.
 *
 * <p>Which half arrives is a property of the account: a usage-based account
 * has {@code spend} and no windows, a Pro or Max account the other way round.
 * Neither may be assumed present, so {@link #isEmpty()} is a legitimate answer
 * rather than a failure.
 *
 * <p>{@code fetchedAt} is when this application received the response; the
 * endpoint does not report it.
 */
public record UsageSnapshot(Instant fetchedAt, Spend spend, List<UsageWindow> windows) {

    public UsageSnapshot {
        windows = List.copyOf(windows);
    }

    public boolean isEmpty() {
        return spend == null && windows.isEmpty();
    }
}
